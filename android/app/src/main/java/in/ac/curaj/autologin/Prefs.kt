package `in`.ac.curaj.autologin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

enum class LastState {
    ONLINE,
    ALREADY_ONLINE,
    SESSION_LIMIT,
    FAILED,
    PAUSED,
    LOGGED_OUT,
    SESSION_CLOSED,
    UNKNOWN
}

/**
 * Credentials live in EncryptedSharedPreferences (AES-256-GCM values,
 * AES-256-SIV keys) backed by the Android Keystore. The last result is kept
 * here too so the UI can show what happened while it was closed.
 *
 * The Keystore key can be invalidated (lock-screen change, backup restore) and
 * the encrypted file can be corrupt. Both are recoverable: the unusable file
 * is dropped, the key entry is removed, and the next read returns a safe
 * fallback so the user can enter the credentials again.
 */
object Prefs {
    private const val TAG = "Prefs"
    private const val FILE = "curaj_secure"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "password"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_LAST_STATE = "last_state"

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var resetNotice = false

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

    private fun isCryptoFailure(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is GeneralSecurityException ||
                cause is IOException ||
                cause is SecurityException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun recover(ctx: Context) {
        synchronized(this) {
            cached = null
            runCatching { ctx.applicationContext.deleteSharedPreferences(FILE) }
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }
            resetNotice = true
        }
    }

    /** True once after storage had to be reset; consumes the flag. */
    fun consumeResetNotice(): Boolean {
        val value = resetNotice
        resetNotice = false
        return value
    }

    private fun <T> read(ctx: Context, fallback: T, block: (SharedPreferences) -> T): T =
        try {
            block(sp(ctx))
        } catch (t: Throwable) {
            if (!isCryptoFailure(t)) throw t
            Log.w(TAG, "encrypted preferences unreadable; resetting storage", t)
            recover(ctx)
            fallback
        }

    private fun write(ctx: Context, block: (SharedPreferences.Editor) -> Unit) {
        try {
            val editor = sp(ctx).edit()
            block(editor)
            editor.apply()
        } catch (t: Throwable) {
            if (!isCryptoFailure(t)) throw t
            Log.w(TAG, "encrypted preferences unwritable; resetting storage", t)
            recover(ctx)
        }
    }

    fun username(ctx: Context): String =
        read(ctx, "") { it.getString(KEY_USER, "").orEmpty() }

    fun password(ctx: Context): String =
        read(ctx, "") { it.getString(KEY_PASS, "").orEmpty() }

    fun enabled(ctx: Context): Boolean =
        read(ctx, true) { it.getBoolean(KEY_ENABLED, true) }

    fun setEnabled(ctx: Context, enabled: Boolean) = write(ctx) { editor ->
        editor.putBoolean(KEY_ENABLED, enabled)
    }

    fun save(ctx: Context, user: String, pass: String, enabled: Boolean) =
        write(ctx) { editor ->
            editor.putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .putBoolean(KEY_ENABLED, enabled)
        }

    fun setUsername(ctx: Context, value: String) = write(ctx) { editor ->
        editor.putString(KEY_USER, value)
    }

    fun setPassword(ctx: Context, value: String) = write(ctx) { editor ->
        editor.putString(KEY_PASS, value)
    }

    fun lastResult(ctx: Context): String =
        read(ctx, "") { it.getString(KEY_LAST_RESULT, "").orEmpty() }

    fun lastTime(ctx: Context): Long =
        read(ctx, 0L) { it.getLong(KEY_LAST_TIME, 0L) }

    fun lastState(ctx: Context): LastState =
        read(ctx, LastState.UNKNOWN) { prefs ->
            runCatching {
                LastState.valueOf(prefs.getString(KEY_LAST_STATE, "").orEmpty())
            }.getOrDefault(LastState.UNKNOWN)
        }

    fun record(ctx: Context, state: LastState, result: String) =
        write(ctx) { editor ->
            editor.putString(KEY_LAST_STATE, state.name)
                .putString(KEY_LAST_RESULT, result)
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
        }
}
