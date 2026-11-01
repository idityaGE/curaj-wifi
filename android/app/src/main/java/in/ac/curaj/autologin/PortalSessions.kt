package `in`.ac.curaj.autologin

import android.net.Network
import android.util.Log
import java.net.URLEncoder

/**
 * Self-care sessions: list and close internet sessions without consuming a
 * third session. The self-care page alone can list but not reliably close;
 * closing also needs the portal-context cookie from the NAS splash.
 */
internal object PortalSessions {
    private const val TAG = "PortalSessions"
    private const val PORTAL_HOST = PortalDiscovery.PORTAL_HOST
    private const val SELF_CARE_LOGIN_PATH =
        "/userportal/pages/usermedia/curaj/app/campus/ui/login.jsp"
    private const val CURRENT_SESSIONS_PATH =
        "/userportal/currentSessions.do?from=Home"

    /**
     * Open the portal's *self-care* login (`type=1`), not the internet login
     * (`type=2`). This management session can list and close internet sessions
     * even when the account already has its two-session maximum, and it does
     * not consume a third session itself.
     */
    fun listSessions(
        network: Network,
        user: String,
        pass: String
    ): PortalClient.SessionsResult {
        if (user.isBlank() || pass.isBlank()) {
            return PortalClient.SessionsResult(
                false,
                message = "no credentials saved"
            )
        }
        val snapshot = selfCareSnapshot(network, user, pass)
        return PortalClient.SessionsResult(
            snapshot.ok,
            snapshot.sessions,
            snapshot.message
        )
    }

    /** Close one chosen session and verify it is gone from the live list. */
    fun logoutSession(
        network: Network,
        user: String,
        pass: String,
        id: String
    ): PortalClient.Result {
        if (!id.matches(Regex("\\d+"))) {
            return PortalClient.Result(false, "session id must be numeric")
        }
        if (user.isBlank() || pass.isBlank()) {
            return PortalClient.Result(false, "no credentials saved")
        }

        val snapshot = selfCareSnapshot(network, user, pass)
        val initialCookie = snapshot.cookie
        if (!snapshot.ok || initialCookie == null) {
            return PortalClient.Result(false, snapshot.message.ifBlank {
                "could not open self-care session"
            })
        }
        var cookie: String = initialCookie
        if (snapshot.sessions.none { it.id == id }) {
            return PortalClient.Result(false, "session $id is not active")
        }

        val response = try {
            PortalHttp.request(
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
                ),
                step = "session_close"
            )
        } catch (e: Exception) {
            return PortalClient.Result(false, "logout request failed: ${e.message}")
        }

        // A 302 to http://1.254.254.254?N=... is not proof: an invalid or
        // unauthorised id gets the same redirect and the session survives.
        // Re-list and only report success when the id is really gone.
        repeat(4) {
            Thread.sleep(800)
            val page = runCatching {
                fetchSessionsPage(network, cookie, step = "verify_sessions_page")
            }.getOrNull()
            if (page != null) {
                val text = page.body
                val validPage =
                    !text.contains("Session has been expired", ignoreCase = true) &&
                        (text.contains("Current Session", ignoreCase = true) ||
                            text.contains("items found", ignoreCase = true))
                if (validPage && parseSessions(text).none { it.id == id }) {
                    return PortalClient.Result(true, "logged out session $id")
                }
            }

            // The context/self-care cookie expired; open a fresh one.
            val after = selfCareSnapshot(network, user, pass)
            if (after.ok && after.sessions.none { it.id == id }) {
                return PortalClient.Result(true, "logged out session $id")
            }
            if (after.ok) after.cookie?.let { cookie = it }
        }

        return PortalClient.Result(
            false,
            "portal did not close session $id (HTTP ${response.code})"
        )
    }

    private data class SelfCareSnapshot(
        val ok: Boolean,
        val cookie: String?,
        val sessions: List<PortalClient.Session>,
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
            return SelfCareSnapshot(
                false, null, emptyList(),
                "unexpected session page (check the saved password)"
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
        val portal = PortalDiscovery.findPortalForContext(network) ?: return null
        if (!PortalDiscovery.isTrustedPortal(portal)) {
            Log.w(
                TAG,
                "refusing portal context from untrusted host: " +
                    PortalHttp.redactUrl(portal)
            )
            return null
        }
        return PortalDiscovery.cookieFrom(
            PortalHttp.request(network, portal, step = "context")
        )
    }

    private fun selfCareCookie(
        network: Network,
        user: String,
        pass: String
    ): String? {
        val base = "http://$PORTAL_HOST"
        val loginPage = base + SELF_CARE_LOGIN_PATH

        // Prefer the context cookie. Fall back to the direct self-care page
        // only so listing still works when the NAS splash is unavailable;
        // closing a session will then fail verification instead of lying.
        var cookie = portalContextCookie(network)
        if (cookie == null) {
            val first = PortalHttp.request(
                network, loginPage, step = "selfcare_login_page"
            )
            cookie = PortalDiscovery.cookieFrom(first) ?: return null
        }

        val form = buildString {
            append("generatePassword=true&phone=0&type=1")
            append("&username=").append(URLEncoder.encode(user, "UTF-8"))
            append("&password=").append(URLEncoder.encode(pass, "UTF-8"))
        }
        val second = PortalHttp.request(
            network,
            "$base/userportal/newlogin.do",
            method = "POST",
            body = form.toByteArray(),
            headers = mapOf(
                "Cookie" to cookie,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to loginPage,
                "Origin" to base
            ),
            step = "selfcare_newlogin"
        )
        PortalDiscovery.cookieFrom(second)?.let { cookie = it }
        return cookie
    }

    private fun fetchSessionsPage(
        network: Network,
        cookie: String,
        step: String = "sessions_page"
    ): PortalHttp.Http =
        PortalHttp.request(
            network,
            "http://$PORTAL_HOST$CURRENT_SESSIONS_PATH",
            headers = mapOf(
                "Cookie" to cookie,
                "Referer" to "http://$PORTAL_HOST/userportal/home.do?from=Home"
            ),
            step = step
        )

    private fun parseSessions(page: String): List<PortalClient.Session> {
        val sessions = mutableListOf<PortalClient.Session>()
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
            sessions += PortalClient.Session(
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
}
