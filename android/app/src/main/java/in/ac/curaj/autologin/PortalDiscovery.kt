package `in`.ac.curaj.autologin

import android.net.Network
import android.os.SystemClock
import android.util.Log
import io.sentry.ISpan
import io.sentry.SpanStatus
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
 *
 * Both the online check and portal discovery race all four probe IPs in
 * parallel. A blackholed endpoint therefore costs one timeout, not four, which
 * is what kept `portal.login` p95 above ten seconds on 1.6.3-alpha1.
 */
internal object PortalDiscovery {
    private const val TAG = "PortalDiscovery"
    const val PORTAL_HOST = "122.252.242.93"

    /**
     * A healthy campus Wi-Fi answers a generate_204 probe in well under a
     * second. Keep the timeout short so a blackholed endpoint cannot dominate
     * login latency; the probes race, so one slow IP is not fatal.
     */
    private const val PROBE_TIMEOUT_MS = 2_500
    private const val PROBE_CONNECT_TIMEOUT_MS = 1_500
    private const val PROBE_READ_TIMEOUT_MS = 2_500

    /**
     * Reuse a positive online result briefly. This absorbs duplicate triggers
     * (network callback plus poll plus manual tap) without hiding a session
     * that expired seconds later: the 60 s service poll always re-checks.
     */
    private const val ONLINE_CACHE_MS = 10_000L

    /** Matches "HTTP/1.x <code>" in the first line of a response head. */
    private val STATUS_LINE = Regex("""(?i)HTTP/\d(?:\.\d)?\s+(\d{3})""")

    /** Google generate_204 endpoints by IP; raced together, no DNS needed. */
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
     * Discovery has its own pool so a still-timing-out online-check loser
     * cannot delay the probe race that immediately follows it.
     */
    private val probeExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(CHECK_URLS.size) { runnable ->
            Thread(runnable, "portal-probe").apply { isDaemon = true }
        }
    }

    private val cacheLock = Any()
    private var cachedNetwork: Network? = null
    private var cachedUntil = 0L

    /**
     * Credentials are only ever posted to the real campus portal. Without
     * this, a hostile Wi-Fi could serve a fake "/userportal" page and harvest
     * the password. Update this set if the university moves the portal.
     */
    private val PORTAL_HOSTS = setOf(PORTAL_HOST)

    private val META = Regex("""(?i)URL=([^"'>\s]+)""")
    private val JS = Regex("""(?i)location(?:\.href)?\s*=\s*["']([^"']+)["']""")
    private val ANY = Regex("""https?://[^"'\s<>]+/userportal/\?[^"'\s<>]+""")

    /**
     * Probe all fixed IPs in parallel and return the first HTTP response.
     *
     * On a captive network the first answer is the portal splash; when the
     * network is already online it is a 204 and [PortalClient] treats it as
     * "already online". A blackholed IP simply never wins the race.
     */
    fun probe(network: Network): PortalHttp.Http {
        val parent = SentryTracing.startSpan("portal.probe", "portal probe", "probe")
        val calls = CHECK_URLS.map { url ->
            val span = SentryTracing.startHttpSpan("GET", url, "probe", parent)
            val connection = ConnectionHandle()
            ProbeCall(
                span,
                {
                    PortalHttp.execute(
                        network = network,
                        url = url,
                        connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = PROBE_READ_TIMEOUT_MS,
                        onOpen = connection::attach
                    )
                },
                connection::cancel
            )
        }
        val completion = ExecutorCompletionService<PortalHttp.Http>(probeExecutor)
        val futures = mutableListOf<Future<PortalHttp.Http>>()
        var last: Exception? = null
        var won = false
        try {
            calls.forEach { futures += completion.submit(it) }
            repeat(calls.size) {
                try {
                    val response = completion.take().get()
                    won = true
                    return response
                } catch (e: ExecutionException) {
                    last = (e.cause ?: e) as? Exception
                }
            }
            throw last ?: IOException("no probe endpoint")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("probe interrupted", e)
        } finally {
            // Losers must not outlive the portal transaction that created
            // them. cancel() disconnects the loser's HTTP connection, which
            // frees its worker thread immediately instead of leaving it parked
            // until the read timeout. Close children before the parent span so
            // the trace tree is complete when it is serialized.
            calls.forEach { it.cancel() }
            futures.forEach { it.cancel(false) }
            parent?.status = if (won) SpanStatus.OK else SpanStatus.INTERNAL_ERROR
            if (!won) last?.let { parent?.throwable = it }
            parent?.finish()
        }
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
                        PortalHttp.request(
                            network,
                            url,
                            step = "discovery",
                            connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = PROBE_READ_TIMEOUT_MS
                        ),
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
                    PortalHttp.request(
                        network,
                        splash,
                        step = "discovery",
                        connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = PROBE_READ_TIMEOUT_MS
                    ),
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
     * Result of the online-check race. [responded] distinguishes a captive
     * portal that answered with a non-204 from a network that produced no
     * response at all. Logout verification must not treat the latter as proof
     * that the session is gone.
     */
    internal data class OnlineResult(val online: Boolean, val responded: Boolean)

    /**
     * Authoritative online check with a short positive cache and an OS hint.
     *
     * The OS already knows whether a network is validated or captive; when it
     * has an opinion, use it instead of spending a probe timeout. The HTTP
     * race remains the fallback and is still what logout verification uses,
     * because logout needs an actual response.
     */
    fun isOnline(network: Network, step: String = "online_check"): Boolean {
        if (cachedOnline(network)) return true

        when (NetworkMonitor.onlineHint(network)) {
            true -> {
                rememberOnline(network)
                return true
            }
            // A captive portal definitely needs a login, but still confirm
            // with the HTTP race so a stale OS hint cannot block a login.
            false, null -> Unit
        }

        val online = checkOnline(network, step).online
        if (online) rememberOnline(network)
        return online
    }

    /**
     * Authoritative online check: 204 from a Google generate_204 endpoint.
     *
     * Only the response head is read - the status line carries everything this
     * check needs (a 204 has no body, and the pre-auth splash body is parsed by
     * findPortal from probe(), not here).
     *
     * All four fixed IPs are raced instead of tried in order. A blackholed
     * endpoint therefore costs one socket timeout, not four, while the first
     * 204 still returns immediately. Losers are cancelled (their sockets are
     * closed) before this method returns so the parent transaction can close
     * safely and the worker threads are free for the next verification poll.
     */
    internal fun checkOnline(
        network: Network,
        step: String = "online_check"
    ): OnlineResult {
        val parent = SentryTracing.startSpan("portal.online_check", "online check", step)
        val checks = CHECK_IPS.map { host ->
            val span = SentryTracing.startHttpSpan(
                "GET",
                "http://connectivitycheck.gstatic.com/generate_204",
                step,
                parent
            )
            span?.setData("server.address", host)
            val socket = SocketHandle()
            OnlineCheck(
                span,
                { proxyFormStatus(network, host, socket) },
                socket::cancel
            )
        }
        val completion = ExecutorCompletionService<Int>(checkExecutor)
        val futures = mutableListOf<Future<Int>>()
        var responded = false
        var online = false
        try {
            checks.forEach { futures += completion.submit(it) }
            repeat(checks.size) {
                val future = completion.take()
                try {
                    val status = future.get()
                    responded = true
                    if (status == 204) {
                        online = true
                        return OnlineResult(true, true)
                    }
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    Log.w(TAG, "online check failed: ${cause.message}")
                }
            }
            return OnlineResult(false, responded)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return OnlineResult(false, false)
        } finally {
            // The loser spans must not outlive the portal transaction that
            // created them. cancel() closes the loser's socket, which frees
            // its worker thread immediately instead of leaving it parked
            // until the probe timeout. Close children before the parent span
            // so the trace tree is complete.
            checks.forEach { it.cancel() }
            futures.forEach { it.cancel(false) }
            parent?.setData("portal.online", online)
            parent?.setData("portal.responded", responded)
            parent?.status = SpanStatus.OK
            parent?.finish()
        }
    }

    /** Drop the positive cache when the underlying network changes. */
    fun invalidateOnlineCache() {
        synchronized(cacheLock) {
            cachedNetwork = null
            cachedUntil = 0L
        }
    }

    private fun cachedOnline(network: Network): Boolean = synchronized(cacheLock) {
        network == cachedNetwork && SystemClock.elapsedRealtime() < cachedUntil
    }

    private fun rememberOnline(network: Network) {
        synchronized(cacheLock) {
            cachedNetwork = network
            cachedUntil = SystemClock.elapsedRealtime() + ONLINE_CACHE_MS
        }
    }

    private fun proxyFormStatus(
        network: Network,
        host: String,
        handle: SocketHandle
    ): Int {
        val socket = network.socketFactory.createSocket()
        handle.attach(socket)
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

    /**
     * Lets the race winner abort the losing sockets instead of leaving them
     * parked in the pool until their timeout. That keeps the next verification
     * poll genuinely parallel even when several IPs are blackholed.
     */
    private class SocketHandle {
        private val lock = Any()
        private var socket: Socket? = null
        private var cancelled = false

        fun attach(value: Socket) {
            val closeNow = synchronized(lock) {
                if (cancelled) {
                    true
                } else {
                    socket = value
                    false
                }
            }
            if (closeNow) runCatching { value.close() }
        }

        fun cancel() {
            val open = synchronized(lock) {
                cancelled = true
                socket.also { socket = null }
            }
            runCatching { open?.close() }
        }
    }

    /** One IP in the online-check race; owns exactly one Sentry span. */
    private class OnlineCheck(
        private val span: ISpan?,
        private val check: () -> Int,
        private val onCancel: () -> Unit
    ) : Callable<Int> {
        private val spanLock = Any()
        private var spanDone = false

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
                // Hold the lock while finishing. If cancel already claimed
                // the span, it has finished it; if this thread claimed it, the
                // caller's cancel blocks until the finish completes. That
                // keeps every child span closed before the parent transaction.
                val error = failure
                synchronized(spanLock) {
                    if (!spanDone) {
                        spanDone = true
                        if (error != null) {
                            SentryTracing.failHttpSpan(span, error)
                        } else {
                            SentryTracing.finishHttpSpan(span, status)
                        }
                    }
                }
            }
        }

        /** Abort the socket and finish a span whose check lost the race. */
        fun cancel() {
            // Claim and finish the span before closing the socket: the close
            // makes the worker throw, and its finally must see spanDone=true
            // so a deliberate cancellation is not reported as a socket error.
            synchronized(spanLock) {
                if (!spanDone) {
                    spanDone = true
                    span?.status = SpanStatus.CANCELLED
                    span?.finish()
                }
            }
            onCancel()
        }
    }

    /** One probe URL in the discovery race; owns exactly one Sentry span. */
    private class ProbeCall(
        private val span: ISpan?,
        private val request: () -> PortalHttp.Http,
        private val onCancel: () -> Unit
    ) : Callable<PortalHttp.Http> {
        private val spanLock = Any()
        private var spanDone = false

        override fun call(): PortalHttp.Http {
            var response: PortalHttp.Http? = null
            var failure: Throwable? = null
            try {
                val value = request()
                response = value
                return value
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                val error = failure
                synchronized(spanLock) {
                    if (!spanDone) {
                        spanDone = true
                        if (error != null) {
                            SentryTracing.failHttpSpan(span, error)
                        } else {
                            SentryTracing.finishHttpSpan(span, response?.code)
                        }
                    }
                }
            }
        }

        /** Abort the connection and finish a span whose probe lost the race. */
        fun cancel() {
            // Claim and finish the span before disconnecting: the disconnect
            // makes the worker throw, and its finally must see spanDone=true
            // so a deliberate cancellation is not reported as a network error.
            synchronized(spanLock) {
                if (!spanDone) {
                    spanDone = true
                    span?.status = SpanStatus.CANCELLED
                    span?.finish()
                }
            }
            onCancel()
        }
    }

    /**
     * Lets the probe winner disconnect the losing HTTP connections instead of
     * leaving them parked in the pool until their read timeout.
     */
    private class ConnectionHandle {
        private val lock = Any()
        private var connection: HttpURLConnection? = null
        private var cancelled = false

        fun attach(value: HttpURLConnection) {
            val closeNow = synchronized(lock) {
                if (cancelled) {
                    true
                } else {
                    connection = value
                    false
                }
            }
            if (closeNow) runCatching { value.disconnect() }
        }

        fun cancel() {
            val open = synchronized(lock) {
                cancelled = true
                connection.also { connection = null }
            }
            runCatching { open?.disconnect() }
        }
    }
}
