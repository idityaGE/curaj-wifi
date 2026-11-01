package `in`.ac.curaj.autologin

import android.net.Network
import android.util.Log
import io.sentry.ISpan
import io.sentry.SpanStatus
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Portal discovery and the authoritative online check.
 *
 * The probe deliberately uses an IP literal. Resolving
 * connectivitycheck.gstatic.com through the captive Wi-Fi returns NXDOMAIN
 * until after login, even though the host is reachable. The portal intercepts
 * any HTTP request, including this one, and answers with its meta-refresh
 * splash page.
 *
 * The online check sends a proxy-form (absolute-URI) request on a raw socket.
 * The campus gateway Squid answers browser-style origin-form requests
 * ("GET /generate_204" + Host:) with 400 ERR_INVALID_URL even for authenticated
 * clients, which would make an online device look blocked forever. The same
 * Squid proxies proxy-form requests normally and still intercepts them with the
 * splash page pre-auth, so only that form distinguishes online/blocked in both
 * states.
 */
internal object PortalDiscovery {
    private const val TAG = "PortalDiscovery"
    const val PORTAL_HOST = "122.252.242.93"

    private const val PROBE_TIMEOUT_MS = 6000

    /** Matches "HTTP/1.x <code>" in the first line of a response head. */
    private val STATUS_LINE = Regex("""(?i)HTTP/\d(?:\.\d)?\s+(\d{3})""")

    /** Google generate_204 endpoints by IP; tried in order, no DNS needed. */
    private val CHECK_IPS = listOf(
        "142.251.150.120",
        "142.250.183.36",
        "142.250.72.196",
        "142.251.32.14"
    )

    private val CHECK_URLS = CHECK_IPS.map { "http://$it/generate_204" }

    /**
     * Daemon threads only: an online check may outlive its caller by at most
     * one socket timeout after the race is won, and must never keep the
     * process alive.
     */
    private val checkExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(CHECK_IPS.size) { runnable ->
            Thread(runnable, "portal-online-check").apply { isDaemon = true }
        }
    }

    /**
     * Credentials are only ever posted to the real campus portal. Without
     * this, a hostile Wi-Fi could serve a fake "/userportal" page and harvest
     * the password. Update this set if the university moves the portal.
     */
    private val PORTAL_HOSTS = setOf(PORTAL_HOST)

    private val META = Regex("""(?i)URL=([^"'>\s]+)""")
    private val JS = Regex("""(?i)location(?:\.href)?\s*=\s*["']([^"']+)["']""")
    private val ANY = Regex("""https?://[^"'\s<>]+/userportal/\?[^"'\s<>]+""")

    fun probe(network: Network): PortalHttp.Http {
        var last: Exception? = null
        for (url in CHECK_URLS) {
            try {
                return PortalHttp.request(network, url, step = "probe")
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "probe failed: ${e.message}")
            }
        }
        throw last ?: IOException("no probe endpoint")
    }

    fun findPortal(
        resp: PortalHttp.Http,
        network: Network
    ): String? {
        val candidates = mutableListOf<String>()
        resp.header("Location")?.let { candidates += it }
        META.find(resp.body)?.groupValues?.get(1)?.let { candidates += it }
        JS.find(resp.body)?.groupValues?.get(1)?.let { candidates += it }
        ANY.findAll(resp.body).forEach { candidates += it.value }

        for (raw in candidates) {
            val cleaned = raw.replace(" ", "%20")
            // meta/JS redirects may be relative; resolve them against the page
            val url = runCatching {
                URI(resp.url).resolve(cleaned).toString()
            }.getOrElse { cleaned }
            if (!url.contains("/userportal") && !url.contains("1.254.254.254")) continue

            if (runCatching { URI(url).host }.getOrNull() == "1.254.254.254") {
                try {
                    val deeper = findPortal(
                        PortalHttp.request(network, url, step = "discovery"),
                        network
                    )
                    if (deeper != null && !deeper.contains("1.254.254.254")) {
                        return deeper
                    }
                } catch (_: Exception) {
                    // fall through and return the splash URL
                }
            }
            return url
        }
        return null
    }

    fun findPortalForLogout(network: Network): String? {
        // While online the probe is 204 and has no portal URL, so the NAS
        // splash is the reliable discovery point; it answers in both states.
        // /login is the reliable splash hop; / sometimes blackholes while online.
        for (splash in listOf("http://1.254.254.254/login", "http://1.254.254.254/")) {
            try {
                val found = findPortal(
                    PortalHttp.request(network, splash, step = "discovery"),
                    network
                )
                if (found != null && found.contains("/userportal")) return found
            } catch (_: Exception) {
                // try the next splash URL
            }
        }
        return null
    }

    fun findPortalForContext(network: Network): String? {
        // Captive: the probe response already carries a fresh sc token.
        try {
            val first = probe(network)
            if (first.code != 204) {
                findPortal(first, network)?.let {
                    if (isTrustedPortal(it)) return it
                }
            }
        } catch (_: Exception) {
            // fall through to the NAS splash
        }
        // Online: the probe is 204 and has no portal URL.
        return findPortalForLogout(network)?.takeIf { isTrustedPortal(it) }
    }

    fun isTrustedPortal(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() }.getOrNull() in PORTAL_HOSTS

    fun cookieFrom(resp: PortalHttp.Http): String? =
        resp.headerAll("Set-Cookie")
            .firstOrNull {
                it.startsWith("JSESSIONID=", ignoreCase = true)
            }
            ?.substringBefore(';')

    /**
     * Authoritative online check: 204 from a Google generate_204 endpoint.
     *
     * Only the response head is read - the status line carries everything this
     * check needs (a 204 has no body, and the pre-auth splash body is parsed by
     * findPortal from probe(), not here).
     *
     * All four fixed IPs are raced instead of tried in order. A blackholed
     * endpoint therefore costs one socket timeout, not four, while the first
     * 204 still returns immediately. Losers are left to time out in the
     * background; their Sentry spans are finished as cancelled before this
     * method returns so the parent transaction can close safely.
     */
    fun isOnline(network: Network, step: String = "online_check"): Boolean {
        val checks = CHECK_IPS.map { host ->
            val span = SentryTracing.startHttpSpan(
                "GET",
                "http://connectivitycheck.gstatic.com/generate_204",
                step
            )
            span?.setData("server.address", host)
            OnlineCheck(span) { proxyFormStatus(network, host) }
        }
        val completion = ExecutorCompletionService<Int>(checkExecutor)
        val futures = mutableListOf<Future<Int>>()
        try {
            checks.forEach { futures += completion.submit(it) }
            repeat(checks.size) {
                val future = completion.take()
                try {
                    if (future.get() == 204) return true
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    Log.w(TAG, "online check failed: ${cause.message}")
                }
            }
            return false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            // The loser spans must not outlive the portal transaction that
            // created them. cancel(false) stops queued checks from starting
            // without interrupting sockets that are already in flight.
            checks.forEach { it.cancelSpan() }
            futures.forEach { it.cancel(false) }
        }
    }

    private fun proxyFormStatus(network: Network, host: String): Int {
        val socket = network.socketFactory.createSocket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, 80), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.getOutputStream().write(
                ("GET http://connectivitycheck.gstatic.com/generate_204 " +
                    "HTTP/1.1\r\n" +
                    "Host: connectivitycheck.gstatic.com\r\n" +
                    "User-Agent: CURAJ-AutoLogin/1.0 (Android)\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            val head = PortalHttp.readResponseHead(socket.getInputStream())
            return STATUS_LINE.find(head)?.groupValues?.get(1)?.toInt()
                ?: throw IOException("unparseable probe response")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** One IP in the online-check race; owns exactly one Sentry span. */
    private class OnlineCheck(
        private val span: ISpan?,
        private val check: () -> Int
    ) : Callable<Int> {
        private val spanDone = AtomicBoolean(false)

        override fun call(): Int {
            var status: Int? = null
            var failure: Throwable? = null
            try {
                val value = check()
                status = value
                return value
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                if (spanDone.compareAndSet(false, true)) {
                    val error = failure
                    if (error != null) {
                        SentryTracing.failHttpSpan(span, error)
                    } else {
                        SentryTracing.finishHttpSpan(span, status)
                    }
                }
            }
        }

        /** Finish a span whose check lost the race or was never started. */
        fun cancelSpan() {
            if (spanDone.compareAndSet(false, true)) {
                span?.status = SpanStatus.CANCELLED
                span?.finish()
            }
        }
    }
}
