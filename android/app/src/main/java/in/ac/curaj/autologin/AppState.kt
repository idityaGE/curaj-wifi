package `in`.ac.curaj.autologin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live state shared by the foreground service and the activity. Persisted
 * history still lives in [Prefs]; this object only avoids polling the service.
 */
object AppState {

    enum class Connection {
        UNKNOWN,
        CHECKING,
        CONNECTING,
        ONLINE,
        CAPTIVE,
        SESSION_LIMIT,
        FAILED,
        OFFLINE,
        PAUSED
    }

    data class State(
        val connection: Connection = Connection.UNKNOWN,
        val message: String = "",
        val updatedAt: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(connection: Connection, message: String = "") {
        _state.value = State(connection, message, System.currentTimeMillis())
    }
}
