package `in`.ac.curaj.autologin

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent foreground service. Three triggers:
 *   - the shared NetworkMonitor callback (fresh Wi-Fi Network)
 *   - a 60 s poll, because some OEMs delay/omit the callback
 *   - the "Login now" notification action
 *
 * Any request that fails is retried by the next poll, so a transient NAS
 * blackhole never leaves the service dead.
 */
class AutoLoginService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private var lastAttempt = 0L
    private var lastNetwork: Network? = null
    private var failures = 0
    private var backoffUntil = 0L
    private var transientRetries = 0
    private var lastNotificationText: String? = null
    private var lastLimit = false
    private var limitNotified = false
    private var foregroundDenied = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val initial = initialText()
        lastNotificationText = initial
        if (!enterForeground(initial)) {
            // Android 12+ only allows startForeground() when the system recorded
            // this start as foreground-eligible. A background resurrection (the
            // OEM killing the app and the system restarting this sticky service,
            // or a plain startService() from a notification action) is not
            // eligible. Running anyway would leave a background service doing
            // network I/O, and every restart would crash the process again, so
            // stop and ask the user to resume from the UI instead.
            foregroundDenied = true
            postResumeNotification()
            stopSelf()
            return
        }
        cancelResumeNotification()
        NetworkMonitor.start(this)

        scope.launch {
            NetworkMonitor.wifi.collect { wifi ->
                if (wifi == null) {
                    synchronized(this@AutoLoginService) { lastNetwork = null }
                    AppState.set(AppState.Connection.OFFLINE, TEXT_DISCONNECTED)
                    update(TEXT_DISCONNECTED)
                } else {
                    attempt(wifi.network)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val network = NetworkMonitor.current()
                if (network == null) {
                    synchronized(this@AutoLoginService) { lastNetwork = null }
                    AppState.set(AppState.Connection.OFFLINE, TEXT_DISCONNECTED)
                    update(TEXT_DISCONNECTED)
                } else {
                    attempt(network)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate() already stopped us: never ask the system to resurrect a
        // service that cannot become a foreground service.
        if (foregroundDenied) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_PAUSE -> {
                Prefs.setEnabled(this, false)
                Prefs.record(this, "Paused by you")
                AppState.set(AppState.Connection.PAUSED, "Auto connect is off")
                cancelLimitNotification()
                cancelResumeNotification()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                Prefs.setEnabled(this, true)
                AppState.set(AppState.Connection.CHECKING, "Resuming…")
            }
        }

        if (!Prefs.enabled(this)) {
            AppState.set(AppState.Connection.PAUSED, "Auto connect is off")
            stopSelf()
            return START_NOT_STICKY
        }

        val force = intent?.action == ACTION_LOGIN_NOW
        val network = NetworkMonitor.current()
        if (network == null) {
            AppState.set(AppState.Connection.OFFLINE, TEXT_DISCONNECTED)
            update(TEXT_DISCONNECTED)
        } else {
            attempt(network, force)
        }
        return START_STICKY
    }

    private fun attempt(network: Network, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (network != lastNetwork) {
                lastNetwork = network
                failures = 0
                transientRetries = 0
                backoffUntil = 0L
                lastAttempt = 0L
                limitNotified = false
            }
            if (!force && now < backoffUntil) return
            if (!force && now - lastAttempt < COOLDOWN_MS) return
            lastAttempt = now
        }
        if (!busy.compareAndSet(false, true)) return

        val user = Prefs.username(this)
        val pass = Prefs.password(this)
        if (user.isBlank() || pass.isBlank()) {
            busy.set(false)
            AppState.set(AppState.Connection.PAUSED, TEXT_NO_CREDENTIALS)
            update(TEXT_NO_CREDENTIALS)
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "attempt network=$network")
                AppState.set(AppState.Connection.CHECKING, "Signing in…")
                val result = SentryTracing.trace(
                    name = "portal.login",
                    operation = "portal",
                    classify = { r: PortalClient.Result ->
                        when {
                            r.ok -> PortalOutcome.OK
                            r.sessionLimit -> PortalOutcome.SESSION_LIMIT
                            r.bindError -> PortalOutcome.BIND_ERROR
                            else -> PortalOutcome.FAILED
                        }
                    }
                ) {
                    SentryTracing.tag("portal.trigger", "auto")
                    PortalClient.login(
                        this@AutoLoginService, network, user, pass
                    )
                }
                Log.i(TAG, "login ok=${result.ok}: ${result.message}")
                when {
                    result.ok -> {
                        transientRetries = 0
                        noteSuccess()
                        cancelLimitNotification()
                        val text = if (result.message == "already online") {
                            "Online (already signed in)"
                        } else {
                            "Online"
                        }
                        Prefs.record(this@AutoLoginService, text)
                        AppState.set(AppState.Connection.ONLINE, text)
                        update(TEXT_CONNECTED)
                    }

                    result.sessionLimit -> {
                        transientRetries = 0
                        noteFailure()
                        Prefs.record(this@AutoLoginService, TEXT_SESSION_LIMIT)
                        AppState.set(
                            AppState.Connection.SESSION_LIMIT,
                            TEXT_SESSION_LIMIT
                        )
                        update(TEXT_SESSION_LIMIT, limit = true)
                    }

                    result.bindError && allowTransientRetry() -> {
                        val fresh = NetworkMonitor.current()
                        if (fresh == null) {
                            synchronized(this@AutoLoginService) {
                                lastNetwork = null
                            }
                            AppState.set(
                                AppState.Connection.OFFLINE,
                                TEXT_DISCONNECTED
                            )
                            update(TEXT_DISCONNECTED)
                        } else {
                            AppState.set(
                                AppState.Connection.CHECKING,
                                "Wi-Fi changed; retrying…"
                            )
                            scope.launch {
                                delay(result.retryMs.coerceAtLeast(800L))
                                attempt(fresh, force = true)
                            }
                        }
                    }

                    result.retryMs > 0 && allowTransientRetry() -> {
                        // The portal is still authenticating this IP. Check again
                        // shortly; do not show a false failure in between.
                        AppState.set(
                            AppState.Connection.CHECKING,
                            "Waiting for portal…"
                        )
                        val fresh = NetworkMonitor.current() ?: network
                        scope.launch {
                            delay(result.retryMs)
                            attempt(fresh, force = true)
                        }
                    }

                    else -> {
                        transientRetries = 0
                        noteFailure()
                        val text = "Sign-in failed: ${result.message}"
                        Prefs.record(this@AutoLoginService, text)
                        AppState.set(AppState.Connection.FAILED, text)
                        update(text)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "login crashed", e)
                Sentry.captureException(e) { scope ->
                    scope.setTag("portal.phase", "login")
                    scope.setTag("portal.result", "exception")
                    scope.setTag("service", "AutoLoginService")
                }
                transientRetries = 0
                noteFailure()
                val text = "Sign-in error: ${e.message ?: e.javaClass.simpleName}"
                Prefs.record(this@AutoLoginService, text)
                AppState.set(AppState.Connection.FAILED, text)
                update(text)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Bound transient loops so they cannot run forever. */
    private fun allowTransientRetry(): Boolean = synchronized(this) {
        if (transientRetries >= 10) {
            false
        } else {
            transientRetries += 1
            true
        }
    }

    private fun noteSuccess() {
        synchronized(this) {
            failures = 0
            backoffUntil = 0L
        }
    }

    /** 1, 2, 4, 8, 15 minutes: a wrong password must not hammer the portal. */
    private fun noteFailure() {
        synchronized(this) {
            failures += 1
            val delayMs = minOf(
                60_000L * (1L shl minOf(failures - 1, 4)),
                15 * 60_000L
            )
            backoffUntil = SystemClock.elapsedRealtime() + delayMs
        }
    }

    /**
     * The OS rejects startForeground() when the app was not eligible to start a
     * foreground service. That is a normal condition for a background restart,
     * so it must never take the process down.
     */
    private fun enterForeground(text: String): Boolean = try {
        startForeground(NOTIF_ID, notification(text))
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "startForeground() denied by the OS", e)
        false
    }

    /**
     * A plain notification is not a foreground one, so it is always allowed.
     * Tapping it opens the activity, which restarts the service while the app
     * is visible, where startForeground() is permitted again.
     */
    private fun postResumeNotification() {
        if (!runCatching { Prefs.enabled(this) }.getOrDefault(false)) return
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Auto-login paused")
            .setContentText("Android stopped the background service. Tap to resume.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Android stopped the background service while the app was " +
                        "not in use. Open CURAJ Auto Login to turn auto-login " +
                        "back on."
                )
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_RESUME_ID, notification)
        }
    }

    private fun cancelResumeNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_RESUME_ID)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL, "Campus auto-login", NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)

            // The limit notification is actionable, so it is allowed to make
            // a sound once. It is separate from the silent foreground state.
            val limit = NotificationChannel(
                CHANNEL_LIMIT, "Session limit",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            limit.setShowBadge(true)
            limit.enableVibration(true)
            nm.createNotificationChannel(limit)
        }
    }

    private fun notification(text: String, limit: Boolean = false): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        if (limit) openIntent.putExtra(MainActivity.EXTRA_MANAGE_SESSIONS, true)
        val open = PendingIntent.getActivity(
            this, if (limit) 4 else 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val loginNow = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, AutoLoginService::class.java).setAction(ACTION_LOGIN_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manage = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MANAGE_SESSIONS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, AutoLoginService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("CURAJ Auto Login")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (limit) {
            builder.addAction(
                R.drawable.ic_sync, "Manage sessions", manage
            )
        } else {
            builder.addAction(
                R.drawable.ic_sync, "Login now", loginNow
            )
        }
        builder.addAction(R.drawable.ic_pause, "Pause", pause)
        return builder.build()
    }

    private fun postLimitNotification() {
        if (limitNotified) return
        limitNotified = true
        val manage = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MANAGE_SESSIONS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_LIMIT)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Session limit reached")
            .setContentText("2 sessions are active. Log out one to reconnect.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "The CURAJ portal allows two active sessions. " +
                        "Tap Manage sessions to log out one device, then " +
                        "the app will try to connect again."
                )
            )
            .setContentIntent(manage)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_sync, "Manage sessions", manage)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_LIMIT_ID, notification)
    }

    private fun cancelLimitNotification() {
        limitNotified = false
        getSystemService(NotificationManager::class.java).cancel(NOTIF_LIMIT_ID)
    }

    private fun initialText(): String {
        if (Prefs.username(this).isBlank()) return TEXT_NO_CREDENTIALS
        if (NetworkMonitor.current() == null) return TEXT_DISCONNECTED
        val last = Prefs.lastResult(this)
        return when {
            last.startsWith("Online") -> TEXT_CONNECTED
            last.startsWith("Session limit") -> TEXT_SESSION_LIMIT
            last.startsWith("Sign-in failed") || last.startsWith("Sign-in error") -> last
            else -> TEXT_RUNNING
        }
    }

    /**
     * The foreground notification is state-only: connected, failed, or
     * disconnected. "Signing in", "waiting for Wi-Fi" and similar transitions
     * are deliberately not posted. Identical states are not re-notified.
     */
    private fun update(text: String, limit: Boolean = false) {
        synchronized(this) {
            if (text == lastNotificationText && limit == lastLimit) return
            lastNotificationText = text
            lastLimit = limit
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(text, limit))
        if (limit) {
            postLimitNotification()
        } else {
            cancelLimitNotification()
        }
    }

    override fun onDestroy() {
        NetworkMonitor.stop(this)
        scope.cancel()
        // A service that ran normally is being stopped on purpose (pause, logout,
        // auto-connect off), so the "paused by Android" hint is stale.
        if (!foregroundDenied) cancelResumeNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AutoLoginService"
        private const val CHANNEL = "auto_login"
        private const val CHANNEL_LIMIT = "session_limit"
        private const val NOTIF_ID = 41
        private const val NOTIF_LIMIT_ID = 42
        private const val NOTIF_RESUME_ID = 43
        private const val POLL_MS = 60_000L
        private const val COOLDOWN_MS = 20_000L

        private const val TEXT_RUNNING = "Auto-login is on"
        private const val TEXT_CONNECTED = "Connected - internet is working"
        private const val TEXT_DISCONNECTED = "Disconnected from campus Wi-Fi"
        private const val TEXT_NO_CREDENTIALS =
            "Set your mobile number and password in the app"
        private const val TEXT_SESSION_LIMIT =
            "Session limit reached - log out one session"

        const val ACTION_LOGIN_NOW = "in.ac.curaj.autologin.LOGIN_NOW"
        const val ACTION_PAUSE = "in.ac.curaj.autologin.PAUSE"
        const val ACTION_RESUME = "in.ac.curaj.autologin.RESUME"
    }
}
