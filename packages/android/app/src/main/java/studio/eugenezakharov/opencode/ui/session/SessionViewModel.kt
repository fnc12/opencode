package studio.eugenezakharov.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.Session

data class SessionUiState(
    val messages: List<MessageWithParts> = emptyList(),
    val revision: Int = 0,
    val status: SessionStore.StreamStatus = SessionStore.StreamStatus.IDLE,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Drives one session's live view: seeds history via REST, then consumes the
 * `GET /global/event` SSE stream (reconnecting with exponential backoff),
 * folding events into a [SessionStore]. Mirrors iOS `SessionView.run()`.
 */
class SessionViewModel(
    private val server: ServerConnection,
    private val session: Session,
) : ViewModel() {

    private val store = SessionStore()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    init {
        store.onChange = {
            _state.value = _state.value.copy(
                messages = store.messages,
                revision = store.revision,
                status = store.status,
            )
        }
        start()
    }

    private fun start() {
        viewModelScope.launch {
            // 1) Seed history.
            val seeded = runCatching { server.messages(session.directory, session.id) }
            seeded.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load messages")
                return@launch
            }
            store.setInitial(seeded.getOrThrow())
            _state.value = _state.value.copy(loading = false, error = null)

            // 2) Stream live with reconnect/backoff.
            var backoffMs = 500L
            while (isActive) {
                val stream = server.eventStream() ?: break
                store.setStatus(SessionStore.StreamStatus.CONNECTING)
                runCatching {
                    stream.frames().collect { data ->
                        backoffMs = 500L // healthy stream resets backoff
                        store.setStatus(SessionStore.StreamStatus.LIVE)
                        ServerEvent.decode(json, data)?.let { store.apply(it, session.id) }
                    }
                }
                if (!isActive) break
                store.setStatus(SessionStore.StreamStatus.RECONNECTING)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
            }
        }
    }
}
