package `in`.ac.curaj.autologin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val enabled = runCatching { Prefs.enabled(ctx) }.getOrDefault(false)
        val user = runCatching { Prefs.username(ctx) }.getOrDefault("")
        val pass = runCatching { Prefs.password(ctx) }.getOrDefault("")
        if (enabled && user.isNotBlank() && pass.isNotBlank()) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, AutoLoginService::class.java)
                )
            }
        }
    }
}
