package `in`.ac.curaj.autologin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Decrypting credentials can touch the Keystore, so keep it off the
        // main thread; goAsync() keeps the receiver alive until finish(). Only
        // start when auto-connect is on and both credentials are present, so a
        // reboot cannot leave a credential-less foreground service running.
        val app = ctx.applicationContext
        val pending = goAsync()
        val worker = Thread {
            try {
                val enabled = runCatching { Prefs.enabled(app) }.getOrDefault(false)
                val user = runCatching { Prefs.username(app) }.getOrDefault("")
                val pass = runCatching { Prefs.password(app) }.getOrDefault("")
                if (enabled && user.isNotBlank() && pass.isNotBlank()) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            app, Intent(app, AutoLoginService::class.java)
                        )
                    }.onFailure {
                        Log.w("BootReceiver", "could not start auto-login", it)
                    }
                }
            } finally {
                pending.finish()
            }
        }
        runCatching { worker.start() }.onFailure {
            Log.w("BootReceiver", "could not start boot worker", it)
            pending.finish()
        }
    }
}
