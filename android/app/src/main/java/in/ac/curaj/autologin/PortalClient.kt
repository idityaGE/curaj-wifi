package `in`.ac.curaj.autologin

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Facade for the campus portal. This object owns the login/logout flow and
 * keeps the public API stable; transport lives in [PortalHttp], discovery and
 * online checks in [PortalDiscovery], and self-care sessions in
 * [PortalSessions].
 *
 * Reproduces exactly what the Inventum/RailWire portal does in a browser:
 *
 *   GET  http://142.251.150.120/generate_204      (must be 204; no DNS)
 *   GET  http://122.252.242.93/userportal/?...&sc=...  (JSESSIONID)
 *   POST http://122.252.242.93/userportal/login.do?requesturi=...
 *   POST http://122.252.242.93/userportal/newlogin.do
 *        username=..&password=..&phone=0&type=2&jsonresponse=1
 *
 * `Network.openConnection` pins the request to the Wi-Fi network, so an
 * active mobile-data connection cannot steal it (the same trap the Linux
 * client solves with SO_BINDTODEVICE).
 */
object PortalClient {
    private const val TAG = "PortalClient"
    private const val REQUEST_URI = "http://connectivitycheck.gstatic.com/generate_204"
    private const val NAS_LOGOUT_URL = "http://1.254.254.254/logout"

    /**
     * After the portal says success, the NAS may still be opening the gate.
     * Poll with a hard deadline instead of fixed sleeps, so a fast gate adds
     * almost no latency and a slow one still cannot run forever.
     */
    private const val VERIFY_TIMEOUT_MS = 8_000L
    private const val VERIFY_POLL_MS = 300L
    private const val LOGOUT_VERIFY_TIMEOUT_MS = 6_000L

    data class Result(
        val ok: Boolean,
        val message: String,
        /** If > 0, retry soon without treating this as a hard failure. */
        val retryMs: Long = 0L,
        /** The account already has its maximum two internet sessions. */
        val sessionLimit: Boolean = false,
        /** The selected Network was stale/not bindable; retry with a fresh one. */
        val bindError: Boolean = false,
        /** Typed outcome; UI and service decisions must use this, not [message]. */
        val kind: Result.Kind = when {
            ok -> Result.Kind.OK
            sessionLimit -> Result.Kind.SESSION_LIMIT
            bindError -> Result.Kind.BIND_ERROR
            retryMs > 0 -> Result.Kind.RETRY
            else -> Result.Kind.FAILED
        }
    ) {
        enum class Kind {
            OK,
            ALREADY_ONLINE,
            SESSION_LIMIT,
            BIND_ERROR,
            RETRY,
            FAILED
        }
    }

    data class Session(
        val id: String,
        val name: String,
        val accountId: String,
        val ratePlan: String,
        val netId: String,
        val startTime: String,
        val duration: String,
        val upload: String,
        val download: String,
        val total: String
    ) {
        fun summary(): String =
            "$startTime\nDuration $duration  ·  $total\n$name ($accountId)"
    }

    data class SessionsResult(
        val ok: Boolean,
        val sessions: List<Session> = emptyList(),
        val message: String = ""
    )

    @Synchronized
    fun login(
        ctx: Context,
        network: Network,
        user: String,
        pass: String,
        depth: Int = 0,
        skipInitialOnlineCheck: Boolean = false
    ): Result {
        return try {
            loginOnce(ctx, network, user, pass, depth, skipInitialOnlineCheck)
        } catch (e: Exception) {
            if (isBindError(e)) {
                Log.w(TAG, "network bind failed; service will re-resolve Wi-Fi", e)
                Sentry.captureException(e) { scope ->
                    scope.setLevel(SentryLevel.WARNING)
                    scope.setTag("network.bind_error", "true")
                    scope.setFingerprint(listOf("network-bind-error"))
                }
                Result(
                    false,
                    "Wi-Fi network is changing",
                    retryMs = 800L,
                    bindError = true,
                    kind = Result.Kind.BIND_ERROR
                )
            } else {
                Log.w(TAG, "login failed: ${e.javaClass.simpleName}")
                Result(
                    false,
                    "login error"
                )
            }
        }
    }

    private fun loginOnce(
        ctx: Context,
        network: Network,
        user: String,
        pass: String,
        depth: Int,
        skipInitialOnlineCheck: Boolean
    ): Result {
        if (user.isBlank() || pass.isBlank()) {
            return Result(false, "no credentials saved")
        }

        // The online check is authoritative and independent of the
        // origin-form probe: post-auth the gateway Squid answers origin-form
        // HTTP with 400, which would look like a captive portal here.
        if (!skipInitialOnlineCheck && isOnline(network)) {
            return Result(
                true,
                "already online",
                kind = Result.Kind.ALREADY_ONLINE
            )
        }

        val first = try {
            PortalDiscovery.probe(network)
        } catch (_: Exception) {
            return Result(false, "probe failed")
        }

        // The network may have come online while the probe was racing. A 204
        // means there is nothing to log in to, so do not fail with "no
        // captive portal" or post credentials to a validated network.
        if (first.code == 204) {
            return Result(
                true,
                "already online",
                kind = Result.Kind.ALREADY_ONLINE
            )
        }

        val portal = PortalDiscovery.findPortal(first, network)
            ?: return Result(false, "no captive portal (HTTP ${first.code})")

        Log.i(TAG, "portal: ${PortalHttp.redactUrl(portal)}")

        val uri = runCatching { URI(portal) }
            .getOrElse { return Result(false, "bad portal URL") }
        val authority = uri.authority ?: return Result(false, "portal host missing")
        val host = uri.host ?: return Result(false, "portal host missing")
        if (!PortalDiscovery.isTrustedPortal(portal)) {
            return Result(false, "refusing to send credentials to $host")
        }
        val requestUri = query(uri.rawQuery)["requesturi"] ?: REQUEST_URI

        // 1. portal session (JSESSIONID carries ip/mac/sc context)
        val splash = try {
            PortalHttp.request(network, portal, step = "splash")
        } catch (_: Exception) {
            return Result(false, "portal unreachable")
        }

        val cookie = PortalDiscovery.cookieFrom(splash)
            ?: return Result(false, "portal issued no JSESSIONID")

        val common = mapOf(
            "Cookie" to cookie,
            "Referer" to "http://$authority/userportal/pages/usermedia/curaj/app/campus/ui/login.html",
            "Origin" to "http://$authority"
        )

        // 2. the same hop the browser makes before showing the form
        try {
            PortalHttp.request(
                network,
                "http://$authority/userportal/login.do?requesturi=" +
                    URLEncoder.encode(requestUri, "UTF-8"),
                method = "POST",
                body = ByteArray(0),
                headers = common,
                step = "login_page"
            )
        } catch (_: Exception) {
            // not strictly required; newlogin.do is the one that counts
        }

        // 3. credentials
        val form = buildString {
            append("username=").append(URLEncoder.encode(user, "UTF-8"))
            append("&password=").append(URLEncoder.encode(pass, "UTF-8"))
            append("&phone=0&type=2&jsonresponse=1")
        }

        val resp = try {
            PortalHttp.request(
                network,
                "http://$authority/userportal/newlogin.do",
                method = "POST",
                body = form.toByteArray(),
                headers = common + ("Content-Type" to "application/x-www-form-urlencoded"),
                step = "newlogin"
            )
        } catch (_: Exception) {
            return Result(false, "login request failed")
        }

        val json = try {
            JSONObject(resp.body)
        } catch (_: Exception) {
            JSONObject()
        }

        val ok = json.optString("errorKey") == "success" ||
            json.optString("errorMessage") == "success_net"

        if (!ok) {
            val reason = json.optString("errorMessage")
                .ifBlank { json.optString("errorKey") }
                .ifBlank { "unexpected portal response (HTTP ${resp.code})" }

            // The account is limited to two simultaneous internet sessions.
            // Never kick one automatically: the user chooses from the app.
            if (isSessionLimit(reason) || isSessionLimit(resp.body)) {
                Log.i(TAG, "portal session limit reached")
                return Result(
                    false,
                    "session limit reached (2 active)",
                    sessionLimit = true,
                    kind = Result.Kind.SESSION_LIMIT
                )
            }

            val stale = json.optString("errorKey") == "redirect_to_nas" ||
                reason.contains("already running", ignoreCase = true)

            // Android's system captive-portal login can be doing the same
            // authentication at the same time. Do not send a second login:
            // wait for the in-flight one and check whether the gate opened.
            val inProgress =
                reason.contains("already in progress", ignoreCase = true) ||
                    reason.contains("authentication is in progress", ignoreCase = true) ||
                    reason.contains("authentication already", ignoreCase = true)

            if (inProgress) {
                Log.i(TAG, "portal is already authenticating this IP; waiting")
                if (awaitOnline(network, VERIFY_TIMEOUT_MS, "verify_online")) {
                    return Result(true, "logged in")
                }
                return Result(
                    false,
                    "portal is still authenticating; retrying",
                    retryMs = 3000L,
                    kind = Result.Kind.RETRY
                )
            }

            if (stale && depth == 0) {
                runCatching {
                    PortalHttp.request(
                        network,
                        "http://$authority/userportal/logout.do",
                        headers = mapOf("Cookie" to cookie),
                        step = "stale_logout"
                    )
                }
                runCatching {
                    PortalHttp.request(network, NAS_LOGOUT_URL, step = "nas_logout")
                }
                // The NAS needs a moment to process the logout before the
                // gate can open; then poll instead of sleeping a fixed 1.5 s.
                Thread.sleep(500L)
                if (awaitOnline(network, VERIFY_TIMEOUT_MS, "verify_online")) {
                    return Result(
                        true,
                        "already online",
                        kind = Result.Kind.ALREADY_ONLINE
                    )
                }
                return loginOnce(
                    ctx,
                    network,
                    user,
                    pass,
                    depth + 1,
                    skipInitialOnlineCheck = true
                )
            }

            return Result(false, "rejected: $reason")
        }

        // 4. the portal sometimes answers before the NAS opens the gate; the
        // NAS can also blackhole briefly while switching. Poll with a hard
        // deadline so the common fast case does not pay a fixed sleep.
        if (awaitOnline(network, VERIFY_TIMEOUT_MS, "verify_online")) {
            return Result(true, "logged in")
        }
        return Result(false, "portal said success but internet is still blocked")
    }

    /**
     * Poll the authoritative online check until it succeeds or [timeoutMs]
     * expires. Each check is bounded by the probe race, so a blackholed
     * network cannot extend the wait by more than one check per poll.
     */
    private fun awaitOnline(network: Network, timeoutMs: Long, step: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isOnline(network, step)) return true
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            Thread.sleep(minOf(VERIFY_POLL_MS, remaining))
        }
        return false
    }

    /**
     * Real logout. A bare /logout is a no-op: the NAS tears down only the
     * session whose JSESSIONID was bound by newlogin.do. So open a fresh
     * portal session, bind it with the saved credentials, then call logout.do
     * with that cookie, and finally the NAS logout as a fallback.
     */
    @Synchronized
    fun logout(network: Network, user: String, pass: String): Result {
        if (user.isBlank() || pass.isBlank()) {
            return Result(false, "no credentials saved")
        }

        val portal = PortalDiscovery.findPortalForLogout(network)
            ?: return Result(false, "campus portal not reachable")

        val uri = runCatching { URI(portal) }.getOrNull()
            ?: return Result(false, "bad portal URL")
        val authority = uri.authority ?: return Result(false, "portal host missing")
        val host = uri.host ?: return Result(false, "portal host missing")
        if (!PortalDiscovery.isTrustedPortal(portal)) {
            return Result(false, "refusing to send credentials to $host")
        }

        val splash = try {
            PortalHttp.request(network, portal, step = "splash")
        } catch (_: Exception) {
            return Result(false, "portal unreachable")
        }
        val cookie = PortalDiscovery.cookieFrom(splash)
            ?: return Result(false, "portal issued no JSESSIONID")

        val common = mapOf(
            "Cookie" to cookie,
            "Referer" to "http://$authority/userportal/pages/usermedia/curaj/app/campus/ui/login.html",
            "Origin" to "http://$authority"
        )
        val form = buildString {
            append("username=").append(URLEncoder.encode(user, "UTF-8"))
            append("&password=").append(URLEncoder.encode(pass, "UTF-8"))
            append("&phone=0&type=2&jsonresponse=1")
        }

        // Bind the fresh JSESSIONID to the live NAS session. The result may
        // legitimately be "session already running"; only the cookie matters.
        runCatching {
            PortalHttp.request(
                network,
                "http://$authority/userportal/newlogin.do",
                method = "POST",
                body = form.toByteArray(),
                headers = common + ("Content-Type" to "application/x-www-form-urlencoded"),
                step = "newlogin"
            )
        }

        val logoutResult = runCatching {
            PortalHttp.request(
                network,
                "http://$authority/userportal/logout.do",
                headers = mapOf("Cookie" to cookie),
                step = "logout"
            )
        }
        Log.i(
            TAG,
            "logout.do -> ${logoutResult.getOrNull()?.code ?: logoutResult.exceptionOrNull()?.javaClass?.simpleName}"
        )

        runCatching {
            PortalHttp.request(network, NAS_LOGOUT_URL, step = "nas_logout")
        }

        // checkOnline (not isOnline) is deliberate: a 400 from the gateway
        // Squid must not be mistaken for "logged out", and a network with no
        // response at all is not evidence either. Fail closed instead.
        val deadline = SystemClock.elapsedRealtime() + LOGOUT_VERIFY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val check = PortalDiscovery.checkOnline(network, "verify_logout")
            if (!check.online && check.responded) {
                return Result(true, "logged out")
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            Thread.sleep(minOf(VERIFY_POLL_MS, remaining))
        }
        return Result(false, "could not verify logout")
    }

    @Synchronized
    fun listSessions(
        network: Network,
        user: String,
        pass: String
    ): SessionsResult = PortalSessions.listSessions(network, user, pass)

    @Synchronized
    fun logoutSession(
        network: Network,
        user: String,
        pass: String,
        id: String
    ): Result = PortalSessions.logoutSession(network, user, pass, id)

    fun isOnline(network: Network, step: String = "online_check"): Boolean =
        PortalDiscovery.isOnline(network, step)

    private fun isBindError(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("EPERM", ignoreCase = true) ||
                message.contains("EACCES", ignoreCase = true) ||
                message.contains("Binding socket", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isSessionLimit(text: String): Boolean {
        val value = text.lowercase()
        return value.contains("maximum session") ||
            value.contains("session allowed reached") ||
            value.contains("maximum of 2") ||
            value.contains("subscription allow")
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
}
