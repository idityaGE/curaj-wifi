package `in`.ac.curaj.autologin

import android.content.Context
import android.net.Network
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
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
 *
 * The probe deliberately uses an IP literal. Resolving
 * connectivitycheck.gstatic.com through the captive Wi-Fi returns NXDOMAIN
 * until after login, even though the host is reachable. The portal intercepts
 * any HTTP request, including this one, and answers with its meta-refresh
 * splash page.
 */
object PortalClient {
    private const val TAG = "PortalClient"
    private const val REQUEST_URI = "http://connectivitycheck.gstatic.com/generate_204"
    private const val NAS_LOGOUT_URL = "http://1.254.254.254/logout"
    private const val PORTAL_HOST = "122.252.242.93"
    private const val SELF_CARE_LOGIN_PATH =
        "/userportal/pages/usermedia/curaj/app/campus/ui/login.jsp"
    private const val CURRENT_SESSIONS_PATH =
        "/userportal/currentSessions.do?from=Home"

    /** Google generate_204 endpoints by IP; tried in order, no DNS needed. */
    private val CHECK_URLS = listOf(
        "http://142.251.150.120/generate_204",
        "http://142.250.183.36/generate_204",
        "http://142.250.72.196/generate_204",
        "http://142.251.32.14/generate_204"
    )

    /**
     * Credentials are only ever posted to the real campus portal. Without
     * this, a hostile Wi-Fi could serve a fake "/userportal" page and harvest
     * the password. Update this set if the university moves the portal.
     */
    private val PORTAL_HOSTS = setOf(PORTAL_HOST)

    data class Result(
        val ok: Boolean,
        val message: String,
        /** If > 0, retry soon without treating this as a hard failure. */
        val retryMs: Long = 0L,
        /** The account already has its maximum two internet sessions. */
        val sessionLimit: Boolean = false
    )

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

    private data class Http(
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

    @Synchronized
    fun login(
        ctx: Context,
        network: Network,
        user: String,
        pass: String,
        depth: Int = 0
    ): Result {
        if (user.isBlank() || pass.isBlank()) {
            return Result(false, "no credentials saved")
        }

        val first = try {
            probe(network)
        } catch (e: Exception) {
            return Result(false, "probe failed: ${e.message}")
        }

        if (first.code == 204) return Result(true, "already online")

        val portal = findPortal(first, network)
            ?: return Result(false, "no captive portal (HTTP ${first.code})")

        Log.i(TAG, "portal: $portal")

        val uri = runCatching { URI(portal) }
            .getOrElse { return Result(false, "bad portal URL: $portal") }
        val authority = uri.authority ?: return Result(false, "portal host missing")
        val host = uri.host ?: return Result(false, "portal host missing")
        if (host !in PORTAL_HOSTS) {
            return Result(false, "refusing to send credentials to $host")
        }
        val requestUri = query(uri.rawQuery)["requesturi"] ?: REQUEST_URI

        // 1. portal session (JSESSIONID carries ip/mac/sc context)
        val splash = try {
            request(network, portal)
        } catch (e: Exception) {
            return Result(false, "portal unreachable: ${e.message}")
        }

        val cookie = splash.headerAll("Set-Cookie")
            .firstOrNull { it.startsWith("JSESSIONID=", ignoreCase = true) }
            ?.substringBefore(';')
            ?: return Result(false, "portal issued no JSESSIONID")

        val common = mapOf(
            "Cookie" to cookie,
            "Referer" to "http://$authority/userportal/pages/usermedia/curaj/app/campus/ui/login.html",
            "Origin" to "http://$authority"
        )

        // 2. the same hop the browser makes before showing the form
        try {
            request(
                network,
                "http://$authority/userportal/login.do?requesturi=" +
                    URLEncoder.encode(requestUri, "UTF-8"),
                method = "POST",
                body = ByteArray(0),
                headers = common
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
            request(
                network,
                "http://$authority/userportal/newlogin.do",
                method = "POST",
                body = form.toByteArray(),
                headers = common + ("Content-Type" to "application/x-www-form-urlencoded")
            )
        } catch (e: Exception) {
            return Result(false, "login POST failed: ${e.message}")
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
                .ifBlank { resp.body.take(160) }
                .ifBlank { "HTTP ${resp.code}" }

            // The account is limited to two simultaneous internet sessions.
            // Never kick one automatically: the user chooses from the app.
            if (isSessionLimit(reason) || isSessionLimit(resp.body)) {
                Log.i(TAG, "portal session limit reached")
                return Result(
                    false,
                    "session limit reached (2 active)",
                    sessionLimit = true
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
                repeat(6) {
                    Thread.sleep(1000)
                    if (runCatching { probe(network).code }.getOrNull() == 204) {
                        return Result(true, "logged in")
                    }
                }
                return Result(
                    false,
                    "portal is still authenticating; retrying",
                    retryMs = 3000L
                )
            }

            if (stale && depth == 0) {
                runCatching {
                    request(
                        network,
                        "http://$authority/userportal/logout.do",
                        headers = mapOf("Cookie" to cookie)
                    )
                }
                runCatching { request(network, "http://1.254.254.254/logout") }
                Thread.sleep(1500)
                val code = runCatching { probe(network).code }.getOrNull()
                if (code == 204) return Result(true, "already online")
                return login(ctx, network, user, pass, depth + 1)
            }

            return Result(false, "rejected: $reason")
        }

        // 4. the portal sometimes answers before the NAS opens the gate
        repeat(6) {
            Thread.sleep(1500)
            try {
                if (probe(network).code == 204) {
                    return Result(true, "logged in")
                }
            } catch (_: Exception) {
                // keep polling
            }
        }
        return Result(false, "portal said success but internet is still blocked")
    }

    /**
     * Real logout. A bare /logout is a no-op: the NAS tears down only the
     * session whose JSESSIONID was bound by newlogin.do. So open a fresh
     * portal session, bind it with the saved credentials, then call logout.do
     * with that cookie, and finally the NAS logout as a fallback.
     */
    fun logout(network: Network, user: String, pass: String): Result {
        if (user.isBlank() || pass.isBlank()) {
            return Result(false, "no credentials saved")
        }

        val portal = findPortalForLogout(network)
            ?: return Result(false, "campus portal not reachable")

        val uri = runCatching { URI(portal) }.getOrNull()
            ?: return Result(false, "bad portal URL: $portal")
        val authority = uri.authority ?: return Result(false, "portal host missing")
        val host = uri.host ?: return Result(false, "portal host missing")
        if (host !in PORTAL_HOSTS) {
            return Result(false, "refusing to send credentials to $host")
        }

        val splash = try {
            request(network, portal)
        } catch (e: Exception) {
            return Result(false, "portal unreachable: ${e.message}")
        }
        val cookie = splash.headerAll("Set-Cookie")
            .firstOrNull { it.startsWith("JSESSIONID=", ignoreCase = true) }
            ?.substringBefore(';')
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
            request(
                network,
                "http://$authority/userportal/newlogin.do",
                method = "POST",
                body = form.toByteArray(),
                headers = common + ("Content-Type" to "application/x-www-form-urlencoded")
            )
        }

        val logoutResult = runCatching {
            request(
                network,
                "http://$authority/userportal/logout.do",
                headers = mapOf("Cookie" to cookie)
            )
        }
        Log.i(TAG, "logout.do -> ${logoutResult.getOrNull()?.code ?: logoutResult.exceptionOrNull()?.message}")

        runCatching { request(network, NAS_LOGOUT_URL) }

        repeat(4) {
            Thread.sleep(1000)
            val code = runCatching { probe(network).code }.getOrNull()
            if (code != null && code != 204) return Result(true, "logged out")
        }
        return Result(false, "could not verify logout")
    }

    /**
     * Open the portal's *self-care* login (`type=1`), not the internet login
     * (`type=2`).  This management session can list and close internet
     * sessions even when the account already has its two-session maximum, and
     * it does not consume a third session itself.
     */
    fun listSessions(
        network: Network,
        user: String,
        pass: String
    ): SessionsResult {
        if (user.isBlank() || pass.isBlank()) {
            return SessionsResult(false, message = "no credentials saved")
        }
        val snapshot = selfCareSnapshot(network, user, pass)
        return SessionsResult(snapshot.ok, snapshot.sessions, snapshot.message)
    }

    /** Close one chosen session and verify it is gone from the live list. */
    fun logoutSession(
        network: Network,
        user: String,
        pass: String,
        id: String
    ): Result {
        if (!id.matches(Regex("\\d+"))) {
            return Result(false, "session id must be numeric")
        }
        if (user.isBlank() || pass.isBlank()) {
            return Result(false, "no credentials saved")
        }

        val snapshot = selfCareSnapshot(network, user, pass)
        val initialCookie = snapshot.cookie
        if (!snapshot.ok || initialCookie == null) {
            return Result(false, snapshot.message.ifBlank {
                "could not open self-care session"
            })
        }
        var cookie: String = initialCookie
        if (snapshot.sessions.none { it.id == id }) {
            return Result(false, "session $id is not active")
        }

        val response = try {
            request(
                network,
                "http://$PORTAL_HOST/userportal/logout.do" +
                    "?from=curses&sesno=$id",
                method = "POST",
                body = ByteArray(0),
                headers = mapOf(
                    "Cookie" to cookie,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to
                        "http://$PORTAL_HOST$CURRENT_SESSIONS_PATH",
                    "Origin" to "http://$PORTAL_HOST"
                )
            )
        } catch (e: Exception) {
            return Result(false, "logout request failed: ${e.message}")
        }

        // A 302 to http://1.254.254.254?N=... is not proof: an invalid or
        // unauthorised id gets the same redirect and the session survives.
        // Re-list and only report success when the id is really gone.
        repeat(4) {
            Thread.sleep(800)
            val page = runCatching { fetchSessionsPage(network, cookie) }
                .getOrNull()
            if (page != null) {
                val text = page.body
                val validPage =
                    !text.contains("Session has been expired", ignoreCase = true) &&
                        (text.contains("Current Session", ignoreCase = true) ||
                            text.contains("items found", ignoreCase = true))
                if (validPage && parseSessions(text).none { it.id == id }) {
                    return Result(true, "logged out session $id")
                }
            }

            // The context/self-care cookie expired; open a fresh one.
            val after = selfCareSnapshot(network, user, pass)
            if (after.ok && after.sessions.none { it.id == id }) {
                return Result(true, "logged out session $id")
            }
            if (after.ok) after.cookie?.let { cookie = it }
        }

        return Result(
            false,
            "portal did not close session $id (HTTP ${response.code})"
        )
    }

    private data class SelfCareSnapshot(
        val ok: Boolean,
        val cookie: String?,
        val sessions: List<Session>,
        val message: String
    )

    private fun selfCareSnapshot(
        network: Network,
        user: String,
        pass: String
    ): SelfCareSnapshot {
        val cookie = try {
            selfCareCookie(network, user, pass)
        } catch (e: Exception) {
            return SelfCareSnapshot(
                false, null, emptyList(),
                "self-care login failed: ${e.message}"
            )
        } ?: return SelfCareSnapshot(
            false, null, emptyList(), "self-care login issued no JSESSIONID"
        )

        var current = cookie
        var page = try {
            fetchSessionsPage(network, current)
        } catch (e: Exception) {
            return SelfCareSnapshot(
                false, null, emptyList(), "session list failed: ${e.message}"
            )
        }

        if (page.body.contains("Session has been expired", ignoreCase = true)) {
            current = try {
                selfCareCookie(network, user, pass)
            } catch (e: Exception) {
                null
            } ?: return SelfCareSnapshot(
                false, null, emptyList(), "self-care session expired"
            )
            page = try {
                fetchSessionsPage(network, current)
            } catch (e: Exception) {
                return SelfCareSnapshot(
                    false, null, emptyList(), "session list failed: ${e.message}"
                )
            }
        }

        if (page.body.contains("Session has been expired", ignoreCase = true)) {
            return SelfCareSnapshot(
                false, null, emptyList(),
                "self-care login failed (check the saved password)"
            )
        }
        if (!page.body.contains("Current Session", ignoreCase = true) &&
            !page.body.contains("items found", ignoreCase = true)
        ) {
            val snippet = cleanCell(page.body).take(160)
            return SelfCareSnapshot(
                false, null, emptyList(), "unexpected session page: $snippet"
            )
        }
        return SelfCareSnapshot(true, current, parseSessions(page.body), "")
    }

    /**
     * The self-care page on its own can list sessions but cannot close them:
     * logout.do then redirects to the NAS splash and leaves the session alive.
     * The cookie from the portal splash URL carries the ip/mac/nas/sc context
     * that the portal requires for a real close, so obtain that first.
     */
    private fun portalContextCookie(network: Network): String? {
        val portal = findPortalForContext(network) ?: return null
        if (!isTrustedPortal(portal)) {
            Log.w(TAG, "refusing portal context from untrusted host: $portal")
            return null
        }
        return cookieFrom(request(network, portal))
    }

    private fun findPortalForContext(network: Network): String? {
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

    private fun isTrustedPortal(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() }.getOrNull() in PORTAL_HOSTS

    private fun selfCareCookie(
        network: Network,
        user: String,
        pass: String
    ): String? {
        val base = "http://$PORTAL_HOST"
        val loginPage = base + SELF_CARE_LOGIN_PATH

        // Prefer the context cookie.  Fall back to the direct self-care page
        // only so listing still works when the NAS splash is unavailable;
        // closing a session will then fail verification instead of lying.
        var cookie = portalContextCookie(network)
        if (cookie == null) {
            val first = request(network, loginPage)
            cookie = cookieFrom(first) ?: return null
        }

        val form = buildString {
            append("generatePassword=true&phone=0&type=1")
            append("&username=").append(URLEncoder.encode(user, "UTF-8"))
            append("&password=").append(URLEncoder.encode(pass, "UTF-8"))
        }
        val second = request(
            network,
            "$base/userportal/newlogin.do",
            method = "POST",
            body = form.toByteArray(),
            headers = mapOf(
                "Cookie" to cookie,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to loginPage,
                "Origin" to base
            )
        )
        cookieFrom(second)?.let { cookie = it }
        return cookie
    }

    private fun fetchSessionsPage(network: Network, cookie: String): Http =
        request(
            network,
            "http://$PORTAL_HOST$CURRENT_SESSIONS_PATH",
            headers = mapOf(
                "Cookie" to cookie,
                "Referer" to "http://$PORTAL_HOST/userportal/home.do?from=Home"
            )
        )

    private fun cookieFrom(resp: Http): String? =
        resp.headerAll("Set-Cookie")
            .firstOrNull {
                it.startsWith("JSESSIONID=", ignoreCase = true)
            }
            ?.substringBefore(';')

    private fun parseSessions(page: String): List<Session> {
        val sessions = mutableListOf<Session>()
        val rowRegex = Regex("(?is)<tr[^>]*>(.*?)</tr>")
        val cellRegex = Regex("(?is)<td[^>]*>(.*?)</td>")
        val idRegex = Regex("""callClose\((\d+)\)""")

        for (row in rowRegex.findAll(page)) {
            val rowHtml = row.groupValues[1]
            val id = idRegex.find(rowHtml)?.groupValues?.get(1) ?: continue
            val cells = cellRegex.findAll(rowHtml)
                .map { cleanCell(it.groupValues[1]) }
                .toList()
            if (cells.size < 9) continue
            sessions += Session(
                id = id,
                name = cells.getOrElse(0) { "" },
                accountId = cells.getOrElse(1) { "" },
                ratePlan = cells.getOrElse(2) { "" },
                netId = cells.getOrElse(3) { "" },
                startTime = cells.getOrElse(4) { "" },
                duration = cells.getOrElse(5) { "" },
                upload = cells.getOrElse(6) { "" },
                download = cells.getOrElse(7) { "" },
                total = cells.getOrElse(8) { "" }
            )
        }
        return sessions
    }

    private fun cleanCell(raw: String): String =
        raw.replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isSessionLimit(text: String): Boolean {
        val value = text.lowercase()
        return value.contains("maximum session") ||
            value.contains("session allowed reached") ||
            value.contains("maximum of 2") ||
            value.contains("subscription allow")
    }

    private fun findPortalForLogout(network: Network): String? {
        // While online the probe is 204 and has no portal URL, so the NAS
        // splash is the reliable discovery point; it answers in both states.
        // /login is the reliable splash hop; / sometimes blackholes while online.
        for (splash in listOf("http://1.254.254.254/login", "http://1.254.254.254/")) {
            try {
                val found = findPortal(request(network, splash), network)
                if (found != null && found.contains("/userportal")) return found
            } catch (_: Exception) {
                // try the next splash URL
            }
        }
        return null
    }

    private fun findPortal(resp: Http, network: Network): String? {
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
                    val deeper = findPortal(request(network, url), network)
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

    private fun probe(network: Network): Http {
        var last: Exception? = null
        for (url in CHECK_URLS) {
            try {
                return request(network, url)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "probe $url failed: ${e.message}")
            }
        }
        throw last ?: IOException("no probe endpoint")
    }

    private fun request(
        network: Network,
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap()
    ): Http {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", "CURAJ-AutoLogin/1.0 (Android)")
        conn.setRequestProperty("Accept", "*/*")
        for ((k, v) in headers) conn.setRequestProperty(k, v)

        if (body != null) {
            conn.doOutput = true
            if (conn.getRequestProperty("Content-Type") == null) {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(body) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.ISO_8859_1)?.use { it.readText() } ?: ""
        val headersOut: Map<String, List<String>> = conn.headerFields.entries
            .filter { it.key != null }
            .associate { it.key!! to it.value }
        conn.disconnect()
        return Http(code, headersOut, text, url)
    }

    private val META = Regex("""(?i)URL=([^"'>\s]+)""")
    private val JS = Regex("""(?i)location(?:\.href)?\s*=\s*["']([^"']+)["']""")
    private val ANY = Regex("""https?://[^"'\s<>]+/userportal/\?[^"'\s<>]+""")

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
}
