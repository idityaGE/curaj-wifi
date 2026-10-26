package `in`.ac.curaj.autologin

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import java.util.Locale

/**
 * Initializes Sentry as early as possible. The DSN comes from a local
 * machine-specific file or SENTRY_DSN; the build-time auth token is only used
 * by Gradle for source-context uploads.
 */
class CurajApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return

        SentryAndroid.init(this) { options ->
            options.setDsn(dsn)
            // Never log event payloads: a debug log must not become a copy of
            // the portal request or its credentials.
            options.setDebug(false)
            options.setEnvironment(if (BuildConfig.DEBUG) "debug" else "production")
            options.setRelease(
                "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            )
            options.setDist(BuildConfig.VERSION_CODE.toString())
            options.setTracesSampleRate(if (BuildConfig.DEBUG) 1.0 else 0.2)

            // This app handles a portal password. Never send default PII,
            // screenshots, view hierarchies, request bodies, or auth headers.
            // Kept for the 8.x SDK; DataCollection replaces it in 9.0.
            @Suppress("DEPRECATION")
            options.setSendDefaultPii(false)
            options.setAttachScreenshot(false)
            options.setAttachViewHierarchy(false)

            options.setBeforeSend(SentryOptions.BeforeSendCallback { event, _ ->
                event.request = null
                event.user = null
                event.extras?.keys?.toList()?.forEach { key ->
                    if (isSensitiveKey(key)) event.extras?.remove(key)
                }
                // Defense in depth: breadcrumbs are normally filtered as they
                // are created, but never send an unsanitized HTTP URL.
                event.breadcrumbs?.forEach { sanitizeHttpBreadcrumb(it) }
                event
            })

            options.setBeforeBreadcrumb(
                SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                    if (breadcrumb.type == "http") {
                        sanitizeHttpBreadcrumb(breadcrumb)
                    }
                    breadcrumb
                }
            )
        }
    }

    private fun sanitizeHttpBreadcrumb(breadcrumb: Breadcrumb) {
        val data = breadcrumb.data ?: return
        data.keys.toList().forEach { key ->
            if (key.equals("http.query", ignoreCase = true) ||
                key.equals("http.fragment", ignoreCase = true) ||
                key.equals("http.data", ignoreCase = true) ||
                key.equals("http.body", ignoreCase = true) ||
                key.equals("http.cookies", ignoreCase = true) ||
                key.equals("http.headers", ignoreCase = true) ||
                isSensitiveKey(key)
            ) {
                data.remove(key)
            }
        }
        val url = data["http.url"]
        if (url is String) {
            data["http.url"] = stripQueryAndFragment(url)
        }
    }

    private fun stripQueryAndFragment(url: String): String {
        var end = url.length
        val query = url.indexOf('?')
        if (query >= 0) end = minOf(end, query)
        val fragment = url.indexOf('#')
        if (fragment >= 0) end = minOf(end, fragment)
        return url.substring(0, end)
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.contains("pass") ||
            normalized.contains("credential") ||
            normalized.contains("cookie") ||
            normalized.contains("authorization") ||
            normalized.contains("username") ||
            normalized.contains("user_name")
    }
}
