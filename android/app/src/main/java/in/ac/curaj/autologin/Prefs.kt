package `in`.ac.curaj.autologin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Credentials live in EncryptedSharedPreferences (AES-256-GCM values,
 * AES-256-SIV keys) backed by the Android Keystore. The last result is kept
 * here too so the UI can show what happened while it was closed.
 */
object Prefs {
    private const val FILE = "curaj_secure"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "password"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_TIME = "last_time"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun sp(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                app,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { cached = it }
        }
    }

    fun username(ctx: Context): String = sp(ctx).getString(KEY_USER, "").orEmpty()
    fun password(ctx: Context): String = sp(ctx).getString(KEY_PASS, "").orEmpty()
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun save(ctx: Context, user: String, pass: String, enabled: Boolean) {
        sp(ctx).edit()
            .putString(KEY_USER, user)
            .putString(KEY_PASS, pass)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun lastResult(ctx: Context): String =
        sp(ctx).getString(KEY_LAST_RESULT, "").orEmpty()

    fun lastTime(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_TIME, 0L)

    fun record(ctx: Context, result: String) {
        sp(ctx).edit()
            .putString(KEY_LAST_RESULT, result)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .apply()
    }
}
