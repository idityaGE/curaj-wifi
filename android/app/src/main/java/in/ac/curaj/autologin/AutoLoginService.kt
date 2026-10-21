package `in`.ac.curaj.autologin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent foreground service. Three triggers:
 *   - ConnectivityManager callback (fires when Wi-Fi appears and when the
 *     system flips NET_CAPABILITY_CAPTIVE_PORTAL / drops VALIDATED)
 *   - a 60 s poll, because some OEMs delay/omit the callback
 *   - the "Login now" notification action
 *
 * Any request that fails is retried by the next poll, so a transient NAS
 * blackhole never leaves the service dead.
 */
class AutoLoginService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private lateinit var cm: ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastAttempt = 0L
    private var lastNetwork: Network? = null
    private var failures = 0
    private var backoffUntil = 0L
    private var transientRetries = 0
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        createChannel()
        val initial = initialText()
        lastNotificationText = initial
        startForeground(NOTIF_ID, notification(initial))

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                attempt(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                val captive = caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                )
                val validated = caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                if (captive || !validated) attempt(network)
            }

            override fun onLost(network: Network) {
                val same = synchronized(this@AutoLoginService) {
                    network == lastNetwork
                }
                if (same) update(TEXT_DISCONNECTED)
            }
        }
        cm.registerNetworkCallback(request, callback!!)

        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                wifiNetwork()?.let { attempt(it) } ?: update(TEXT_DISCONNECTED)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                Prefs.setEnabled(this, false)
                Prefs.record(this, "Paused by you")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> Prefs.setEnabled(this, true)
        }

        if (!Prefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val force = intent?.action == ACTION_LOGIN_NOW
        wifiNetwork()?.let { attempt(it, force) } ?: update(TEXT_DISCONNECTED)
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
            update(TEXT_NO_CREDENTIALS)
            return
        }

        scope.launch {
            try {
                val result = PortalClient.login(this@AutoLoginService, network, user, pass)
                Log.i(TAG, "login ok=${result.ok}: ${result.message}")
                if (result.ok) {
                    transientRetries = 0
                    noteSuccess()
                    val text = if (result.message == "already online") {
                        "Online (already signed in)"
                    } else {
                        "Online"
                    }
                    Prefs.record(this@AutoLoginService, text)
                    update(TEXT_CONNECTED)
                } else if (result.retryMs > 0 && allowTransientRetry()) {
                    // The portal is still authenticating this IP. Check again
                    // shortly; do not show a false failure in between.
                    scope.launch {
                        delay(result.retryMs)
                        attempt(network, force = true)
                    }
                } else {
                    transientRetries = 0
                    noteFailure()
                    val text = "Sign-in failed: ${result.message}"
                    Prefs.record(this@AutoLoginService, text)
                    update(text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "login crashed", e)
                transientRetries = 0
                noteFailure()
                val text = "Sign-in error: ${e.message ?: e.javaClass.simpleName}"
                Prefs.record(this@AutoLoginService, text)
                update(text)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Bound the "portal is authenticating" loop so it cannot run forever. */
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

    private fun wifiNetwork(): Network? {
        cm.activeNetwork?.let { active ->
            if (cm.getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) {
                return active
            }
        }
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "Campus auto-login", NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val loginNow = PendingIntent.getService(
            this, 1,
            Intent(this, AutoLoginService::class.java).setAction(ACTION_LOGIN_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 2,
            Intent(this, AutoLoginService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("CURAJ Auto Login")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_sync, "Login now", loginNow
            )
            .addAction(
                R.drawable.ic_pause, "Pause", pause
            )
            .build()
    }

    private fun initialText(): String {
        if (Prefs.username(this).isBlank()) return TEXT_NO_CREDENTIALS
        if (wifiNetwork() == null) return TEXT_DISCONNECTED
        val last = Prefs.lastResult(this)
        return when {
            last.startsWith("Online") -> TEXT_CONNECTED
            last.startsWith("Sign-in failed") || last.startsWith("Sign-in error") -> last
            else -> TEXT_RUNNING
        }
    }

    /**
     * The foreground notification is state-only: connected, failed, or
     * disconnected. "Signing in", "waiting for Wi-Fi" and similar transitions
     * are deliberately not posted. Identical states are not re-notified.
     */
    private fun update(text: String) {
        synchronized(this) {
            if (text == lastNotificationText) return
            lastNotificationText = text
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(text))
    }

    override fun onDestroy() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AutoLoginService"
        private const val CHANNEL = "auto_login"
        private const val NOTIF_ID = 41
        private const val POLL_MS = 60_000L
        private const val COOLDOWN_MS = 20_000L

        private const val TEXT_RUNNING = "Auto-login is on"
        private const val TEXT_CONNECTED = "Connected - internet is working"
        private const val TEXT_DISCONNECTED = "Disconnected from campus Wi-Fi"
        private const val TEXT_NO_CREDENTIALS =
            "Set your mobile number and password in the app"

        const val ACTION_LOGIN_NOW = "in.ac.curaj.autologin.LOGIN_NOW"
        const val ACTION_PAUSE = "in.ac.curaj.autologin.PAUSE"
        const val ACTION_RESUME = "in.ac.curaj.autologin.RESUME"
    }
}
