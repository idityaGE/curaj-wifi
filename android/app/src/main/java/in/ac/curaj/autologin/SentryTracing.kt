package `in`.ac.curaj.autologin

import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import java.net.URI

/**
 * How a portal operation ended. The tag is sent as `portal.result`; the status
 * makes expected failures visible instead of reporting every transaction as
 * `ok`.
 */
enum class PortalOutcome(val tag: String, val status: SpanStatus) {
    OK("ok", SpanStatus.OK),
    FAILED("failed", SpanStatus.UNKNOWN),
    SESSION_LIMIT("session_limit", SpanStatus.RESOURCE_EXHAUSTED),
    BIND_ERROR("bind_error", SpanStatus.UNAVAILABLE)
}

/**
 * Transaction and span helpers for the blocking portal calls. Portal calls run
 * synchronously on one background thread, so the current transaction is kept in
 * a [ThreadLocal] and [PortalClient] can attach an `http.client` child span to
 * every request without threading a span through every helper.
 *
 * If portal code ever suspends or hops threads inside [trace], replace the
 * thread-local with explicit span passing.
 */
object SentryTracing {
    private val currentTransaction = ThreadLocal<ITransaction?>()

    fun <T> trace(
        name: String,
        operation: String,
        classify: (T) -> PortalOutcome = { PortalOutcome.OK },
        block: () -> T
    ): T {
        if (!Sentry.isEnabled()) return block()

        val transaction = Sentry.startTransaction(
            name,
            operation,
            TransactionOptions().apply { setBindToScope(true) }
        )
        transaction.setTag("portal.operation", name.removePrefix("portal."))
        currentTransaction.set(transaction)

        return try {
            val value = block()
            val outcome = classify(value)
            transaction.setTag("portal.result", outcome.tag)
            transaction.status = outcome.status
            value
        } catch (e: Throwable) {
            transaction.setTag("portal.result", "exception")
            transaction.throwable = e
            transaction.status = SpanStatus.INTERNAL_ERROR
            throw e
        } finally {
            currentTransaction.remove()
            transaction.finish()
        }
    }

    /**
     * Start a child span for one HTTP request. Returns `null` when no portal
     * transaction is active, so callers can use it unconditionally.
     */
    fun startHttpSpan(method: String, url: String, step: String? = null): ISpan? {
        val parent = currentTransaction.get() ?: return null
        val span = parent.startChild("http.client", httpDescription(method, url))
        span.setData("http.request.method", method)
        span.setData("http.url", sanitizeUrl(url))
        if (step != null) span.setData("portal.step", step)
        return span
    }

    fun finishHttpSpan(span: ISpan?, statusCode: Int? = null) {
        if (span == null) return
        if (statusCode != null) span.setData("http.response.status_code", statusCode)
        span.status = SpanStatus.OK
        span.finish()
    }

    fun failHttpSpan(span: ISpan?, throwable: Throwable) {
        if (span == null) return
        span.throwable = throwable
        span.status = SpanStatus.INTERNAL_ERROR
        span.finish()
    }

    fun tag(key: String, value: String) {
        currentTransaction.get()?.setTag(key, value)
    }

    fun data(key: String, value: Any) {
        currentTransaction.get()?.setData(key, value)
    }

    private fun sanitizeUrl(url: String): String {
        val cut = listOf(url.indexOf('?'), url.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull()
        return if (cut == null) url else url.substring(0, cut)
    }

    private fun httpDescription(method: String, url: String): String {
        val path = runCatching { URI(url).rawPath }
            .getOrNull()
            .orEmpty()
            .ifBlank { "/" }
        return "$method $path"
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
            scope.setTag("portal.result", "exception")
        }
    }
}
