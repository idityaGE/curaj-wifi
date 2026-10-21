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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

        wifiSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        if (Prefs.enabled(this) && Prefs.username(this).isNotBlank()) {
            ContextCompat.startForegroundService(
                this, Intent(this, AutoLoginService::class.java)
            )
        }
        askNotificationPermission()
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

    private fun refreshStatus() {
        if (testing) return
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
}
