package `in`.ac.curaj.autologin

import io.sentry.Sentry
import io.sentry.SpanStatus

/**
 * Lightweight transaction wrapper for the blocking portal calls. These are
 * network-bound and run on a background dispatcher, so a plain transaction is
 * enough and keeps the portal timing separate from app-start tracing.
 */
object SentryTracing {
    inline fun <T> trace(name: String, operation: String, block: () -> T): T {
        if (!Sentry.isEnabled()) return block()
        val transaction = Sentry.startTransaction(name, operation)
        transaction.setTag("portal.operation", name.removePrefix("portal."))
        return try {
            block().also { transaction.status = SpanStatus.OK }
        } catch (e: Throwable) {
            transaction.throwable = e
            transaction.status = SpanStatus.INTERNAL_ERROR
            throw e
        } finally {
            transaction.finish()
        }
    }

    /**
     * Capture an exception that was handled by the UI layer. The portal
     * operations return expected failures as Result values; this is only for
     * unexpected exceptions that would otherwise disappear into a snackbar.
     */
    fun captureHandled(operation: String, trigger: String, throwable: Throwable) {
        if (!Sentry.isEnabled()) return
        Sentry.captureException(throwable) { scope ->
            scope.setTag("portal.operation", operation)
            scope.setTag("portal.trigger", trigger)
        }
    }
}
