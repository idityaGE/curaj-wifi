package `in`.ac.curaj.autologin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var enabled: MaterialSwitch
    private lateinit var toolbar: MaterialToolbar

    private lateinit var wifiName: TextView
    private lateinit var connectionState: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var networkSummary: TextView
    private lateinit var statusDot: View
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectNow: MaterialButton
    private lateinit var sessions: MaterialButton

    private lateinit var networkUpdated: TextView
    private lateinit var networkProgress: ProgressBar
    private lateinit var refreshNetwork: ImageButton
    private lateinit var metricSignal: TextView
    private lateinit var metricLatency: TextView
    private lateinit var metricLoss: TextView
    private lateinit var metricLink: TextView
    private lateinit var metricDownload: TextView
    private lateinit var metricUpload: TextView
    private lateinit var networkPermission: View
    private lateinit var enableWifiDetails: MaterialButton

    private lateinit var savedHint: TextView
    private lateinit var accountError: TextView
    private lateinit var lastResult: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val metricsSampler = NetworkMetricsSampler()
    private var metrics = NetworkMetrics()
    private var manualBusy = false
    private var networkRefreshing = true
    private var initializing = true
    private var switchGuard = false
    private var saveRunnable: Runnable? = null
    private var savedRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        username.setText(Prefs.username(this))
        password.setText(Prefs.password(this))
        enabled.isChecked = Prefs.enabled(this)

        username.doAfterTextChanged { scheduleSave() }
        password.doAfterTextChanged { scheduleSave() }
        enabled.setOnCheckedChangeListener { _, checked ->
            if (!initializing) onAutoConnectChanged(checked)
        }

        connectNow.setOnClickListener {
            if (AppState.state.value.connection == AppState.Connection.ONLINE) {
                confirmLogout()
            } else {
                connectNow()
            }
        }
        sessions.setOnClickListener { openSessions() }
        refreshNetwork.setOnClickListener {
            networkRefreshing = true
            networkProgress.isVisible = true
            refreshStatus()
        }
        enableWifiDetails.setOnClickListener { requestWifiDetailsPermission() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_battery -> {
                    openBatterySettings()
                    true
                }
                R.id.action_wifi -> {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    true
                }
                R.id.action_about -> {
                    showAbout()
                    true
                }
                R.id.action_logout -> {
                    confirmLogout()
                    true
                }
                else -> false
            }
        }

        if (Prefs.enabled(this) &&
            Prefs.username(this).isNotBlank() &&
            Prefs.password(this).isNotBlank()
        ) {
            startServiceQuietly()
        }
        askNotificationPermission()

        if (intent?.getBooleanExtra(EXTRA_MANAGE_SESSIONS, false) == true) {
            intent.removeExtra(EXTRA_MANAGE_SESSIONS)
            window.decorView.post { openSessions() }
        }

        initializing = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppState.state.collect { refreshStatus() }
                }
                launch {
                    NetworkMonitor.wifi.collect {
                        renderNetwork()
                        refreshStatus()
                    }
                }
                launch { metricsLoop() }
            }
        }
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        NetworkMonitor.start(this)
        refreshStatus()
    }

    override fun onStop() {
        NetworkMonitor.stop(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_MANAGE_SESSIONS, false)) {
            intent.removeExtra(EXTRA_MANAGE_SESSIONS)
            // Wait until onStart() has restarted NetworkMonitor; otherwise a
            // notification tap from the background would see no Wi-Fi.
            window.decorView.post { openSessions() }
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        usernameLayout = findViewById(R.id.username_layout)
        passwordLayout = findViewById(R.id.password_layout)
        enabled = findViewById(R.id.enabled)

        wifiName = findViewById(R.id.wifi_name)
        connectionState = findViewById(R.id.connection_state)
        connectionDetail = findViewById(R.id.connection_detail)
        networkSummary = findViewById(R.id.network_summary)
        statusDot = findViewById(R.id.status_dot)
        connectionProgress = findViewById(R.id.connection_progress)
        connectNow = findViewById(R.id.connect_now)
        sessions = findViewById(R.id.sessions)

        networkUpdated = findViewById(R.id.network_updated)
        networkProgress = findViewById(R.id.network_progress)
        refreshNetwork = findViewById(R.id.refresh_network)
        metricSignal = findViewById(R.id.metric_signal)
        metricLatency = findViewById(R.id.metric_latency)
        metricLoss = findViewById(R.id.metric_loss)
        metricLink = findViewById(R.id.metric_link)
        metricDownload = findViewById(R.id.metric_download)
        metricUpload = findViewById(R.id.metric_upload)
        networkPermission = findViewById(R.id.network_permission)
        enableWifiDetails = findViewById(R.id.enable_wifi_details)

        savedHint = findViewById(R.id.saved_hint)
        accountError = findViewById(R.id.account_error)
        lastResult = findViewById(R.id.last_result)
    }

    private suspend fun metricsLoop() {
        while (true) {
            val network = NetworkMonitor.current()
            if (network == null) {
                metrics = NetworkMetrics()
                networkRefreshing = false
                networkProgress.isVisible = false
                renderMetrics()
                delay(5_000L)
                continue
            }
            if (metrics.updatedAt == 0L || networkRefreshing) {
                networkProgress.isVisible = true
            }
            val sampled = metricsSampler.sample(network)
            networkRefreshing = false
            networkProgress.isVisible = false
            metrics = sampled
            renderMetrics()
            delay(5_000L)
        }
    }

    private fun renderNetwork() {
        val wifi = NetworkMonitor.wifi.value
        val details = wifi?.details
        wifiName.text = when {
            details?.ssid != null -> details.ssid
            wifi != null -> getString(R.string.campus_wifi)
            else -> "No Wi-Fi"
        }
        val summary = listOfNotNull(
            details?.ipAddress,
            bandLabel(details?.frequencyMhz),
            details?.rssi?.let { "$it dBm" }
        ).joinToString(" · ")
        networkSummary.text = summary.ifBlank { "—" }

        metricSignal.text = details?.rssi?.let { rssi ->
            val percent = details.signalPercent?.let { "$it%" } ?: "—"
            "${signalBars(details.level)} $percent\n$rssi dBm"
        } ?: "—"
        metricLink.text = details?.linkSpeedMbps?.let { "$it Mbps" } ?: "—"

        networkPermission.isVisible = wifi != null &&
            details?.ssid == null &&
            !NetworkMonitor.hasDetailsPermission(this)
        val iconColor = if (wifi != null) {
            R.color.brand_blue
        } else {
            R.color.status_gray
        }
        findViewById<ImageView>(R.id.wifi_icon).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, iconColor))
    }

    private fun renderMetrics() {
        metricLatency.text = metrics.latencyMs?.let { "$it ms" } ?: "—"
        metricLoss.text = metrics.lossPercent?.let { "$it%  (5 probes)" } ?: "—"
        metricDownload.text = formatBps(metrics.downloadBps)
        metricUpload.text = formatBps(metrics.uploadBps)
        networkUpdated.text = if (metrics.updatedAt == 0L) {
            "—"
        } else {
            DateUtils.getRelativeTimeSpanString(
                metrics.updatedAt,
                System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        }
    }

    private fun refreshStatus() {
        if (!::connectionDetail.isInitialized) return
        renderNetwork()
        renderMetrics()

        val wifi = NetworkMonitor.wifi.value
        val state = AppState.state.value
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        val hasCreds = user.isNotEmpty() && pass.isNotEmpty()
        val active = enabled.isChecked
        val busy = manualBusy ||
            state.connection == AppState.Connection.CHECKING ||
            state.connection == AppState.Connection.CONNECTING

        connectionProgress.isVisible = busy && hasCreds
        connectNow.isEnabled = !busy
        sessions.isEnabled = hasCreds && !manualBusy
        sessions.text = if (state.connection == AppState.Connection.SESSION_LIMIT) {
            "Sessions · 2"
        } else {
            getString(R.string.sessions)
        }

        val connected = state.connection == AppState.Connection.ONLINE && wifi != null
        connectNow.text = if (connected) {
            getString(R.string.log_out)
        } else {
            getString(R.string.connect_now)
        }
        val backgroundAttr = if (connected) {
            com.google.android.material.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorPrimary
        }
        val foregroundAttr = if (connected) {
            com.google.android.material.R.attr.colorOnError
        } else {
            com.google.android.material.R.attr.colorOnPrimary
        }
        connectNow.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(connectNow, backgroundAttr)
        )
        connectNow.setTextColor(MaterialColors.getColor(connectNow, foregroundAttr))

        when {
            !hasCreds -> {
                setStatus(
                    "Set your account",
                    "Add your mobile number and password",
                    R.color.status_gray
                )
            }
            manualBusy -> {
                setStatus(
                    "Working…",
                    state.message.ifBlank { getString(R.string.checking_connection) },
                    R.color.status_amber
                )
            }
            connected -> {
                setStatus(
                    "Connected",
                    "Internet is working",
                    R.color.status_green
                )
            }
            state.connection == AppState.Connection.SESSION_LIMIT -> {
                setStatus(
                    "Session limit reached",
                    "2 active sessions. Close one to reconnect.",
                    R.color.status_amber
                )
            }
            state.connection == AppState.Connection.CHECKING ||
                state.connection == AppState.Connection.CONNECTING -> {
                setStatus(
                    "Signing in…",
                    state.message.ifBlank { getString(R.string.checking_connection) },
                    R.color.status_amber
                )
            }
            state.connection == AppState.Connection.FAILED -> {
                setStatus(
                    "Sign-in failed",
                    state.message.ifBlank { "Tap Connect now to retry" },
                    R.color.status_red
                )
            }
            !active -> {
                setStatus(
                    "Auto connect is off",
                    "Turn it on to stay connected",
                    R.color.status_gray
                )
            }
            wifi == null -> {
                setStatus(
                    "Waiting for campus Wi-Fi",
                    "Connect to CURAJ CAMPUS CONNECT",
                    R.color.status_gray
                )
            }
            state.connection == AppState.Connection.OFFLINE -> {
                setStatus(
                    "Disconnected",
                    "Waiting for campus Wi-Fi",
                    R.color.status_gray
                )
            }
            state.connection == AppState.Connection.CAPTIVE -> {
                setStatus(
                    "Portal login required",
                    "Tap Connect now",
                    R.color.status_amber
                )
            }
            else -> {
                setStatus(
                    "Watching the portal",
                    "Auto connect is on",
                    R.color.brand_blue
                )
            }
        }

        val last = Prefs.lastResult(this)
        val timestamp = Prefs.lastTime(this)
        lastResult.text = if (last.isBlank()) {
            getString(R.string.no_attempts)
        } else if (timestamp > 0) {
            val relative = DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            "$last · $relative"
        } else {
            last
        }
    }

    private fun setStatus(state: String, detail: String, color: Int) {
        connectionState.text = state
        connectionDetail.text = detail
        statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun scheduleSave() {
        saveRunnable?.let(handler::removeCallbacks)
        saveRunnable = Runnable { saveNow(showHint = true) }
        handler.postDelayed(saveRunnable!!, 500L)
    }

    private fun saveNow(showHint: Boolean) {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        Prefs.setUsername(this, user)
        Prefs.setPassword(this, pass)
        Prefs.setEnabled(this, enabled.isChecked)
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            usernameLayout.error = null
            passwordLayout.error = null
            accountError.isVisible = false
            if (showHint) {
                savedHint.isVisible = true
                savedRunnable?.let(handler::removeCallbacks)
                savedRunnable = Runnable { savedHint.isVisible = false }
                handler.postDelayed(savedRunnable!!, 2_000L)
            }
        }
    }

    private fun onAutoConnectChanged(checked: Boolean) {
        if (switchGuard) return
        if (!checked) {
            Prefs.setEnabled(this, false)
            stopService(Intent(this, AutoLoginService::class.java))
            AppState.set(AppState.Connection.PAUSED, "Auto connect is off")
            refreshStatus()
            return
        }

        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isBlank() || pass.isBlank()) {
            switchGuard = true
            enabled.isChecked = false
            switchGuard = false
            showAccountError("Enter both the mobile number and password.")
            Prefs.setEnabled(this, false)
            refreshStatus()
            return
        }

        saveNow(showHint = false)
        Prefs.setEnabled(this, true)
        askNotificationPermission()
        AppState.set(AppState.Connection.CHECKING, "Starting…")
        startServiceQuietly(force = true)
        refreshStatus()
    }

    private fun connectNow() {
        saveNow(showHint = false)
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (!valid(user, pass)) return
        askNotificationPermission()
        if (NetworkMonitor.current() == null) {
            setStatus(
                "Waiting for campus Wi-Fi",
                "Connect to CURAJ CAMPUS CONNECT",
                R.color.status_gray
            )
            return
        }

        if (enabled.isChecked) {
            AppState.set(AppState.Connection.CHECKING, "Signing in…")
            startServiceQuietly(force = true)
            refreshStatus()
        } else {
            runOneShot(user, pass)
        }
    }

    private fun runOneShot(user: String, pass: String) {
        val network = NetworkMonitor.current()
        if (network == null) {
            setStatus(
                "Waiting for campus Wi-Fi",
                "Connect to CURAJ CAMPUS CONNECT",
                R.color.status_gray
            )
            return
        }
        manualBusy = true
        AppState.set(AppState.Connection.CHECKING, "Signing in…")
        refreshStatus()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PortalClient.login(this@MainActivity, network, user, pass)
                }.getOrElse {
                    PortalClient.Result(false, it.message ?: it.javaClass.simpleName)
                }
            }
            val text = when {
                result.ok && result.message == "already online" ->
                    "Online (already signed in)"
                result.ok -> "Online"
                result.sessionLimit -> "Session limit reached"
                else -> "Sign-in failed: ${result.message}"
            }
            Prefs.record(this@MainActivity, text)
            when {
                result.ok -> AppState.set(AppState.Connection.ONLINE, text)
                result.sessionLimit -> AppState.set(
                    AppState.Connection.SESSION_LIMIT, text
                )
                else -> AppState.set(AppState.Connection.FAILED, text)
            }
            manualBusy = false
            if (!isFinishing && !isDestroyed) refreshStatus()
        }
    }

    private fun openSessions() {
        saveNow(showHint = false)
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (!valid(user, pass)) return
        if (supportFragmentManager.findFragmentByTag("sessions") != null) return
        if (NetworkMonitor.current() == null) {
            setStatus(
                "Waiting for campus Wi-Fi",
                "Connect to CURAJ CAMPUS CONNECT",
                R.color.status_gray
            )
            return
        }
        SessionsSheet().apply {
            onChanged = {
                if (!isFinishing && !isDestroyed) refreshStatus()
            }
        }.show(supportFragmentManager, "sessions")
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Log out?")
            .setMessage(
                "This closes the portal session and turns off auto connect " +
                    "so the app does not sign you straight back in."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.log_out) { _, _ -> doLogout() }
            .show()
    }

    private fun doLogout() {
        saveNow(showHint = false)
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (!valid(user, pass)) return

        switchGuard = true
        enabled.isChecked = false
        switchGuard = false
        Prefs.setEnabled(this, false)
        stopService(Intent(this, AutoLoginService::class.java))

        val network = NetworkMonitor.current()
        if (network == null) {
            AppState.set(AppState.Connection.PAUSED, "Logged out")
            refreshStatus()
            return
        }

        manualBusy = true
        AppState.set(AppState.Connection.CHECKING, "Logging out…")
        refreshStatus()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PortalClient.logout(network, user, pass)
                }.getOrElse {
                    PortalClient.Result(false, it.message ?: it.javaClass.simpleName)
                }
            }
            val text = if (result.ok) "Logged out" else "Logout failed: ${result.message}"
            Prefs.record(this@MainActivity, text)
            AppState.set(
                if (result.ok) AppState.Connection.PAUSED else AppState.Connection.FAILED,
                text
            )
            manualBusy = false
            if (!isFinishing && !isDestroyed) refreshStatus()
        }
    }

    private fun valid(user: String, pass: String): Boolean {
        if (user.isEmpty() || pass.isEmpty()) {
            showAccountError("Enter both the mobile number and password.")
            return false
        }
        return true
    }

    private fun showAccountError(message: String) {
        accountError.text = message
        accountError.isVisible = true
        if (username.text.isNullOrBlank()) {
            usernameLayout.error = "Required"
        } else if (password.text.isNullOrEmpty()) {
            passwordLayout.error = "Required"
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_privacy)
            .setMessage(
                "CURAJ Auto Login ${BuildConfig.VERSION_NAME}\n\n" +
                    "Your mobile number and password are stored only on this " +
                    "device, encrypted with the Android Keystore.\n\n" +
                    "Credentials are sent only to 122.252.242.93. The campus " +
                    "portal uses plain HTTP, so use a password you do not reuse " +
                    "elsewhere.\n\n" +
                    "The app recognises the campus portal by what it serves, not " +
                    "by the Wi-Fi name. Wi-Fi details are optional."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun requestWifiDetailsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(NetworkMonitor.detailsPermission()),
            101
        )
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            renderNetwork()
            refreshStatus()
        }
    }

    private fun startServiceQuietly(force: Boolean = false) {
        val intent = Intent(this, AutoLoginService::class.java)
        if (force) intent.action = AutoLoginService.ACTION_LOGIN_NOW
        ContextCompat.startForegroundService(this, intent)
    }

    private fun bandLabel(frequencyMhz: Int?): String? = when {
        frequencyMhz == null -> null
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5900 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> null
    }

    private fun signalBars(level: Int?): String = when (level ?: 0) {
        0 -> "▁"
        1 -> "▁▃"
        2 -> "▁▃▅"
        3 -> "▁▃▅▇"
        else -> "▂▄▆█"
    }

    private fun formatBps(bps: Long?): String {
        if (bps == null) return "—"
        return when {
            bps < 1024L -> "$bps B/s"
            bps < 1024L * 1024L -> "${bps / 1024L} KB/s"
            else -> String.format(
                Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0)
            )
        }
    }

    override fun onDestroy() {
        saveRunnable?.let(handler::removeCallbacks)
        savedRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MANAGE_SESSIONS =
            "in.ac.curaj.autologin.MANAGE_SESSIONS"
    }
}
