package `in`.ac.curaj.autologin

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var enabled: Switch
    private lateinit var status: TextView
    private lateinit var lastResult: TextView
    private var testing = false
    private var sessionsBusy = false

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        enabled = findViewById(R.id.enabled)
        status = findViewById(R.id.status)
        lastResult = findViewById(R.id.last_result)
        val save = findViewById<Button>(R.id.save)
        val test = findViewById<Button>(R.id.test)
        val logout = findViewById<Button>(R.id.logout)
        val sessions = findViewById<Button>(R.id.sessions)
        val battery = findViewById<Button>(R.id.battery)
        val wifiSettings = findViewById<Button>(R.id.wifi_settings)
        val showPassword = findViewById<CheckBox>(R.id.show_password)

        username.setText(Prefs.username(this))
        password.setText(Prefs.password(this))
        enabled.isChecked = Prefs.enabled(this)

        showPassword.setOnCheckedChangeListener { _, checked ->
            password.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            password.setSelection(password.text.length)
        }

        save.setOnClickListener {
            val user = username.text.toString().trim()
            val pass = password.text.toString()
            if (!valid(user, pass)) return@setOnClickListener

            Prefs.save(this, user, pass, enabled.isChecked)
            askNotificationPermission()

            if (enabled.isChecked) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AutoLoginService::class.java)
                        .setAction(AutoLoginService.ACTION_LOGIN_NOW)
                )
                status.text = "Saved. Auto-login is running in the background."
            } else {
                stopService(Intent(this, AutoLoginService::class.java))
                status.text = "Saved. Background auto-login is off."
            }
        }

        test.setOnClickListener {
            val user = username.text.toString().trim()
            val pass = password.text.toString()
            if (!valid(user, pass)) return@setOnClickListener

            Prefs.save(this, user, pass, enabled.isChecked)
            val network = wifiNetwork()
            if (network == null) {
                status.text = "Not connected to Wi-Fi."
                return@setOnClickListener
            }

            testing = true
            status.text = "Testing the portal login…"
            lastResult.text = "Running a one-shot login now."
            Thread {
                val result = runCatching {
                    PortalClient.login(this, network, user, pass)
                }.getOrElse {
                    PortalClient.Result(false, it.message ?: it.javaClass.simpleName)
                }
                val text = when {
                    result.ok && result.message == "already online" ->
                        "Online (already signed in)"
                    result.ok -> "Online"
                    else -> "Sign-in failed: ${result.message}"
                }
                runCatching { Prefs.record(this, text) }
                runOnUiThread {
                    testing = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshStatus()
                }
            }.start()
        }

        battery.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { startActivity(intent) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }

        logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log out?")
                .setMessage(
                    "This closes the portal session and turns off background " +
                        "auto-login so the app does not sign you straight back in."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Log out") { _, _ -> doLogout() }
                .show()
        }

        sessions.setOnClickListener { showSessionsDialog() }

        wifiSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        if (Prefs.enabled(this) && Prefs.username(this).isNotBlank()) {
            ContextCompat.startForegroundService(
                this, Intent(this, AutoLoginService::class.java)
            )
        }
        askNotificationPermission()

        if (intent?.getBooleanExtra(EXTRA_MANAGE_SESSIONS, false) == true) {
            intent.removeExtra(EXTRA_MANAGE_SESSIONS)
            window.decorView.post { showSessionsDialog() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_MANAGE_SESSIONS, false)) {
            intent.removeExtra(EXTRA_MANAGE_SESSIONS)
            showSessionsDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun valid(user: String, pass: String): Boolean {
        if (user.isEmpty() || pass.isEmpty()) {
            status.text = "Enter both the mobile number and password."
            return false
        }
        return true
    }

    private fun doLogout() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (!valid(user, pass)) return

        val network = wifiNetwork()

        // Stop auto-login first, otherwise the service would log straight
        // back in after the portal session is closed.
        Prefs.setEnabled(this, false)
        enabled.isChecked = false
        stopService(Intent(this, AutoLoginService::class.java))

        if (network == null) {
            status.text = "Not connected to Wi-Fi. Auto-login is off."
            return
        }

        testing = true
        status.text = "Logging out…"
        lastResult.text = "Closing the portal session…"

        Thread {
            val result = runCatching {
                PortalClient.logout(network, user, pass)
            }.getOrElse {
                PortalClient.Result(false, it.message ?: it.javaClass.simpleName)
            }
            val text = if (result.ok) {
                "Logged out"
            } else {
                "Logout failed: ${result.message}"
            }
            runCatching { Prefs.record(this, text) }
            runOnUiThread {
                testing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshStatus()
            }
        }.start()
    }

    private fun showSessionsDialog() {
        if (sessionsBusy) return
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (!valid(user, pass)) return
        val network = wifiNetwork()
        if (network == null) {
            status.text = "Not connected to Wi-Fi."
            return
        }

        sessionsBusy = true
        status.text = "Loading active sessions…"
        Thread {
            val result = runCatching {
                PortalClient.listSessions(network, user, pass)
            }.getOrElse {
                PortalClient.SessionsResult(
                    false,
                    message = it.message ?: it.javaClass.simpleName
                )
            }
            runOnUiThread {
                sessionsBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!result.ok) {
                    status.text = "Could not load sessions: ${result.message}"
                    return@runOnUiThread
                }
                refreshStatus()
                showSessionChooser(result.sessions)
            }
        }.start()
    }

    private fun showSessionChooser(sessions: List<PortalClient.Session>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 12)
        }
        val scroll = ScrollView(this).apply { addView(list) }

        val count = TextView(this).apply {
            text = if (sessions.isEmpty()) {
                "No active portal sessions."
            } else {
                "${sessions.size} active portal session(s). " +
                    "Log out one to free a slot."
            }
            textSize = 15f
            setPadding(0, 0, 0, 8)
        }
        list.addView(count)

        lateinit var dialog: AlertDialog
        sessions.forEachIndexed { index, session ->
            val row = TextView(this).apply {
                text = session.summary()
                textSize = 14f
                setPadding(0, 16, 0, 4)
            }
            list.addView(row)

            val button = Button(this).apply {
                text = "Log out this session"
                setOnClickListener {
                    confirmLogoutSession(session, dialog)
                }
            }
            list.addView(button)

            if (index < sessions.lastIndex) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0x22000000)
                })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Active portal sessions")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Refresh") { _, _ -> showSessionsDialog() }
            .create()
        dialog.show()
    }

    private fun confirmLogoutSession(
        session: PortalClient.Session,
        parent: AlertDialog
    ) {
        AlertDialog.Builder(this)
            .setTitle("Log out this session?")
            .setMessage(
                "${session.summary()}\n\n" +
                    "If this is the phone you are using now, its Wi-Fi " +
                    "connection will drop. Auto-login will try to sign " +
                    "back in."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Log out") { _, _ ->
                doLogoutSession(session, parent)
            }
            .show()
    }

    private fun doLogoutSession(
        session: PortalClient.Session,
        parent: AlertDialog
    ) {
        if (sessionsBusy) return
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (!valid(user, pass)) return
        val network = wifiNetwork()
        if (network == null) {
            status.text = "Not connected to Wi-Fi."
            return
        }

        sessionsBusy = true
        status.text = "Logging out session…"
        Thread {
            val result = runCatching {
                PortalClient.logoutSession(
                    network, user, pass, session.id
                )
            }.getOrElse {
                PortalClient.Result(
                    false, it.message ?: it.javaClass.simpleName
                )
            }
            runOnUiThread {
                sessionsBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!result.ok) {
                    status.text = "Could not log out session: ${result.message}"
                    return@runOnUiThread
                }

                parent.dismiss()
                Prefs.record(
                    this,
                    "Logged out session ${session.startTime}"
                )
                if (Prefs.enabled(this)) {
                    status.text = "Session ended. Reconnecting…"
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, AutoLoginService::class.java)
                            .setAction(AutoLoginService.ACTION_LOGIN_NOW)
                    )
                } else {
                    status.text = "Session ended. Auto-login is OFF."
                }
            }
        }.start()
    }

    private fun refreshStatus() {
        if (testing || sessionsBusy) return
        val hasCreds = Prefs.username(this).isNotBlank() && Prefs.password(this).isNotBlank()
        status.text = when {
            !hasCreds -> "No credentials saved yet."
            !Prefs.enabled(this) -> "Auto-login is OFF."
            wifiNetwork() != null ->
                "Auto-login is ON. Wi-Fi connected; watching the portal."
            else -> "Auto-login is ON. Waiting for Wi-Fi."
        }

        val last = Prefs.lastResult(this)
        val timestamp = Prefs.lastTime(this)
        lastResult.text = when {
            last.isBlank() -> "No attempts yet."
            timestamp > 0 ->
                "$last\n${DateFormat.getDateTimeInstance().format(Date(timestamp))}"
            else -> last
        }
    }

    private fun wifiNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
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

    companion object {
        const val EXTRA_MANAGE_SESSIONS =
            "in.ac.curaj.autologin.MANAGE_SESSIONS"
    }
}
