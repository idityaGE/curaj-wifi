package `in`.ac.curaj.autologin

import android.app.Application
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

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
            options.setDebug(BuildConfig.DEBUG)
            options.setEnvironment(if (BuildConfig.DEBUG) "debug" else "production")
            options.setRelease(
                "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            )
            options.setDist(BuildConfig.VERSION_CODE.toString())
            options.setTracesSampleRate(if (BuildConfig.DEBUG) 1.0 else 0.2)

            // This app handles a portal password. Never send default PII,
            // screenshots, view hierarchies, request bodies, or auth headers.
            options.setSendDefaultPii(false)
            options.setAttachScreenshot(false)
            options.setAttachViewHierarchy(false)

            options.setBeforeSend(SentryOptions.BeforeSendCallback { event, _ ->
                event.request = null
                event.user = null
                event.extras?.keys?.toList()?.forEach { key ->
                    if (isSensitiveKey(key)) event.extras?.remove(key)
                }
                event
            })

            options.setBeforeBreadcrumb(
                SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                    if (breadcrumb.type == "http") {
                        breadcrumb.data?.let { data ->
                            data.keys.toList().forEach { key ->
                                if (key.equals("http.query", ignoreCase = true) ||
                                    isSensitiveKey(key)
                                ) {
                                    data.remove(key)
                                }
                            }
                        }
                    }
                    breadcrumb
                }
            )
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("pass") ||
            normalized.contains("credential") ||
            normalized.contains("cookie") ||
            normalized.contains("authorization") ||
            normalized.contains("username") ||
            normalized.contains("user_name")
    }
}
