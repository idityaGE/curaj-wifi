package `in`.ac.curaj.autologin

import io.sentry.Sentry

/**
 * Lightweight transaction wrapper for the blocking portal calls. These are
 * network-bound and run on a background dispatcher, so a plain transaction is
 * enough and keeps the portal timing separate from app-start tracing.
 */
object SentryTracing {
    inline fun <T> trace(name: String, operation: String, block: () -> T): T {
        val transaction = Sentry.startTransaction(name, operation)
        transaction.setTag("portal.operation", name.removePrefix("portal."))
        return try {
            block()
        } catch (e: Throwable) {
            transaction.throwable = e
            throw e
        } finally {
            transaction.finish()
        }
    }
}
