package `in`.ac.curaj.autologin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Active portal sessions with an explicit per-row logout. A close is only
 * reported after the portal confirms the session disappeared from the list,
 * then the sheet refreshes itself automatically.
 */
class SessionsSheet : BottomSheetDialogFragment() {

    var onChanged: (() -> Unit)? = null

    private lateinit var count: TextView
    private lateinit var progress: ProgressBar
    private lateinit var container: LinearLayout
    private lateinit var empty: TextView
    private lateinit var error: TextView
    private lateinit var retry: MaterialButton
    private lateinit var status: TextView
    private lateinit var reconnect: MaterialButton
    private lateinit var refresh: ImageButton

    private var loading = false
    private var closingId: String? = null
    private var autoRefresh: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_sessions, parent, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        count = view.findViewById(R.id.sessions_count)
        progress = view.findViewById(R.id.sessions_progress)
        container = view.findViewById(R.id.sessions_container)
        empty = view.findViewById(R.id.sessions_empty)
        error = view.findViewById(R.id.sessions_error)
        retry = view.findViewById(R.id.sessions_retry)
        status = view.findViewById(R.id.sessions_status)
        reconnect = view.findViewById(R.id.sessions_reconnect)
        refresh = view.findViewById(R.id.sessions_refresh)

        view.findViewById<ImageButton>(R.id.sessions_close)
            .setOnClickListener { dismiss() }
        refresh.setOnClickListener { loadSessions() }
        retry.setOnClickListener { loadSessions() }
        reconnect.setOnClickListener {
            startReconnect()
            reconnect.isVisible = false
            status.text = getString(R.string.refreshing)
            status.isVisible = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                if (!loading && closingId == null) loadSessions(silent = true)
            }
        }.also { autoRefresh = it }

        loadSessions()
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog as? BottomSheetDialog ?: return
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
    }

    override fun onDestroyView() {
        autoRefresh?.cancel()
        autoRefresh = null
        super.onDestroyView()
    }

    private fun loadSessions(silent: Boolean = false) {
        if (loading || closingId != null) return
        val user = Prefs.username(requireContext())
        val pass = Prefs.password(requireContext())
        if (user.isBlank() || pass.isBlank()) {
            showError("Set your mobile number and password first.")
            return
        }
        val network = NetworkMonitor.current()
        if (network == null) {
            showError("Waiting for campus Wi-Fi.")
            return
        }

        loading = true
        refresh.isEnabled = false
        if (silent) {
            progress.isVisible = false
        } else {
            progress.isVisible = true
            empty.isVisible = false
            error.isVisible = false
            retry.isVisible = false
            status.isVisible = false
            reconnect.isVisible = false
            if (container.childCount == 0) count.text = "…"
        }
        if (!silent) count.text = getString(R.string.checking_sessions)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SentryTracing.trace("portal.sessions.list", "portal") {
                        PortalClient.listSessions(network, user, pass)
                    }
                }.getOrElse {
                    PortalClient.SessionsResult(
                        false,
                        message = it.message ?: it.javaClass.simpleName
                    )
                }
            }
            if (!isAdded) return@launch
            loading = false
            progress.isVisible = false
            refresh.isEnabled = true
            if (!result.ok) {
                showError("Could not load sessions: ${result.message}")
                return@launch
            }
            render(result.sessions)
            count.text = "${result.sessions.size} of 2 slots used"
            error.isVisible = false
            retry.isVisible = false
            if (result.sessions.isEmpty()) {
                empty.isVisible = true
                container.removeAllViews()
            } else {
                empty.isVisible = false
            }
        }
    }

    private fun render(sessions: List<PortalClient.Session>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (session in sessions) {
            val row = inflater.inflate(R.layout.item_session, container, false)
            row.tag = session.id
            row.findViewById<TextView>(R.id.session_started).text =
                "Started ${session.startTime.ifBlank { "unknown" }}"
            row.findViewById<TextView>(R.id.session_meta).text =
                "${session.duration.ifBlank { "—" }} · ${session.total.ifBlank { "—" }}"
            row.findViewById<TextView>(R.id.session_account).text =
                if (session.name.isNotBlank()) {
                    "${session.name} · ${session.accountId}"
                } else {
                    "Session ${session.id}"
                }

            val progressView = row.findViewById<ProgressBar>(R.id.session_progress)
            val button = row.findViewById<MaterialButton>(R.id.session_logout)
            val busy = closingId == session.id
            progressView.isVisible = busy
            button.isEnabled = closingId == null
            button.text = if (busy) "Closing…" else getString(R.string.log_out)
            button.setOnClickListener { confirmClose(session) }
            container.addView(row)
        }
    }

    private fun confirmClose(session: PortalClient.Session) {
        if (closingId != null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Log out this session?")
            .setMessage(
                "${session.summary()}\n\n" +
                    "If this is the phone you are using now, its Wi-Fi " +
                    "connection will drop. Auto connect will try to sign " +
                    "back in."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.log_out) { _, _ -> close(session) }
            .show()
    }

    private fun close(session: PortalClient.Session) {
        val user = Prefs.username(requireContext())
        val pass = Prefs.password(requireContext())
        val network = NetworkMonitor.current()
        if (user.isBlank() || pass.isBlank() || network == null) {
            showError("Waiting for campus Wi-Fi or credentials.")
            return
        }

        closingId = session.id
        renderLast()
        status.text = "Closing session…"
        status.isVisible = true
        reconnect.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SentryTracing.trace("portal.sessions.close", "portal") {
                        PortalClient.logoutSession(network, user, pass, session.id)
                    }
                }.getOrElse {
                    PortalClient.Result(false, it.message ?: it.javaClass.simpleName)
                }
            }
            if (!isAdded) return@launch
            closingId = null
            if (result.ok) {
                Prefs.record(requireContext(), "Logged out session ${session.startTime}")
                onChanged?.invoke()
                status.text = getString(R.string.session_closed)
                status.isVisible = true
                if (Prefs.enabled(requireContext())) {
                    startReconnect()
                    status.text = "${getString(R.string.session_closed)} · Reconnecting…"
                }
                loadSessions(silent = true)
            } else {
                showError("Could not log out session: ${result.message}")
                renderLast()
            }
        }
    }

    /** Re-render without fetching (used while a close is in progress). */
    private fun renderLast() {
        // The rows are recreated by the next successful load. For the immediate
        // busy state, simply refresh the existing buttons in place.
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val button = row.findViewById<MaterialButton>(R.id.session_logout)
            val spinner = row.findViewById<ProgressBar>(R.id.session_progress)
            val busy = row.tag == closingId
            button.isEnabled = closingId == null
            spinner.isVisible = busy
            button.text = if (busy) "Closing…" else getString(R.string.log_out)
        }
    }

    private fun showError(message: String) {
        loading = false
        progress.isVisible = false
        refresh.isEnabled = true
        error.text = message
        error.isVisible = true
        retry.isVisible = true
        status.isVisible = false
        reconnect.isVisible = false
        if (container.childCount == 0) {
            count.text = "—"
            empty.isVisible = false
        }
    }

    private fun startReconnect() {
        if (!Prefs.enabled(requireContext())) return
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), AutoLoginService::class.java)
                .setAction(AutoLoginService.ACTION_LOGIN_NOW)
        )
    }
}
