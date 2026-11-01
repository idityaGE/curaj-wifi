package `in`.ac.curaj.autologin

import android.net.Network
import java.io.InputStream
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded HTTP transport for portal calls.
 *
 * Every request is pinned to the supplied Wi-Fi [Network] and every response
 * body is capped, so a hostile or broken portal cannot grow memory without
 * bound. Redirects are never followed: callers inspect Location themselves.
 *
 * The default timeouts are deliberately short. The portal lives on the campus
 * LAN, so a connect that takes seconds is a dead endpoint. Probe callers pass
 * even shorter timeouts through [execute] because the online check races four
 * endpoints and must not wait for a blackholed one.
 */
internal object PortalHttp {
    private const val MAX_HEAD_CHARS = 32_000
    private const val MAX_RESPONSE_CHARS = 1_048_576

    /** Default for real portal calls: the LAN answers fast, POSTs can be slower. */
    internal const val CONNECT_TIMEOUT_MS = 2_000
    internal const val READ_TIMEOUT_MS = 5_000

    data class Http(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val url: String
    ) {
        fun header(name: String): String? = headerAll(name).firstOrNull()

        fun headerAll(name: String): List<String> =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value.orEmpty()
    }

    /**
     * Sentry-aware request used by the portal flow. The span is created on the
     * calling thread so it stays a child of the active portal transaction.
     */
    fun request(
        network: Network,
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        step: String? = null,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Http {
        val span = SentryTracing.startHttpSpan(method, url, step)
        return try {
            val result = execute(
                network,
                url,
                method,
                body,
                headers,
                connectTimeoutMs,
                readTimeoutMs
            )
            SentryTracing.finishHttpSpan(span, result.code)
            result
        } catch (e: Throwable) {
            SentryTracing.failHttpSpan(span, e)
            throw e
        }
    }

    /**
     * Raw request without Sentry bookkeeping.
     *
     * Parallel callers (the probe race) create their spans on the caller
     * thread and pass them to their worker, because [SentryTracing] keeps the
     * current transaction in a ThreadLocal.
     */
    fun execute(
        network: Network,
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        onOpen: ((HttpURLConnection) -> Unit)? = null
    ): Http {
        var conn: HttpURLConnection? = null
        try {
            val opened = network.openConnection(URL(url)) as HttpURLConnection
            conn = opened
            opened.instanceFollowRedirects = false
            opened.connectTimeout = connectTimeoutMs
            opened.readTimeout = readTimeoutMs
            opened.requestMethod = method
            opened.setRequestProperty("User-Agent", "CURAJ-AutoLogin/1.0 (Android)")
            opened.setRequestProperty("Accept", "*/*")
            for ((k, v) in headers) opened.setRequestProperty(k, v)

            // Publish the connection before the first blocking operation so a
            // losing race can disconnect it and free this worker immediately.
            onOpen?.invoke(opened)

            if (body != null) {
                opened.doOutput = true
                if (opened.getRequestProperty("Content-Type") == null) {
                    opened.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                    )
                }
                opened.outputStream.use { it.write(body) }
            }

            val code = opened.responseCode
            val stream = if (code in 200..299) opened.inputStream else opened.errorStream
            val text = stream?.bufferedReader(Charsets.ISO_8859_1)
                ?.use { readLimited(it) } ?: ""
            val headersOut: Map<String, List<String>> = opened.headerFields.entries
                .filter { it.key != null }
                .associate { it.key!! to it.value }
            return Http(code, headersOut, text, url)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Read up to the blank line that ends the HTTP response head. */
    fun readResponseHead(input: InputStream): String {
        val head = StringBuilder()
        val chunk = ByteArray(4096)
        while (head.length < MAX_HEAD_CHARS) {
            val n = input.read(chunk)
            if (n < 0) break
            head.append(String(chunk, 0, n, Charsets.ISO_8859_1))
            if (head.contains("\r\n\r\n")) break
        }
        return head.toString()
    }

    /** Read at most [MAX_RESPONSE_CHARS], so an endless body stays bounded. */
    private fun readLimited(reader: Reader): String {
        val out = StringBuilder()
        val chunk = CharArray(8192)
        while (out.length < MAX_RESPONSE_CHARS) {
            val want = minOf(chunk.size, MAX_RESPONSE_CHARS - out.length)
            val n = reader.read(chunk, 0, want)
            if (n < 0) break
            out.append(chunk, 0, n)
        }
        return out.toString()
    }

    /** Drop the query and fragment so logs never carry sc/session context. */
    fun redactUrl(url: String): String {
        val cut = listOf(url.indexOf('?'), url.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull()
        return if (cut == null) url else url.substring(0, cut)
    }
}
