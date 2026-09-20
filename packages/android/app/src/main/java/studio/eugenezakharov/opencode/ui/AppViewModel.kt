package studio.eugenezakharov.opencode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ConnectionStore
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.models.MessageInfo

data class AppUiState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connected: Boolean = false,
    val version: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * App-level connection state, mirroring iOS `ServerConnection` + `App.swift`.
 * Owns the [ServerConnection] used by every screen, restores the saved config
 * on launch, and persists it on a successful connect.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ConnectionStore(app)
    val server = ServerConnection(store.load() ?: ConnectionConfig())

    private val _state = MutableStateFlow(AppUiState(config = server.config))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** Session ids currently generating a reply, tracked off the global event bus
     *  so the session *list* can show a live "working" indicator even for sessions
     *  that aren't open. Mirrors iOS `ServerConnection.busySessions`. */
    private val _busySessions = MutableStateFlow<Set<String>>(emptySet())
    val busySessions: StateFlow<Set<String>> = _busySessions.asStateFlow()

    /** Set when the user taps a push notification: the id of the session it's
     *  about. `AppNav` observes this, resolves the session and deep-links to it,
     *  then clears it. Mirrors iOS `ServerConnection.pendingOpenSessionID`. */
    private val _pendingOpenSessionId = MutableStateFlow<String?>(null)
    val pendingOpenSessionId: StateFlow<String?> = _pendingOpenSessionId.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var activityJob: Job? = null
    private var connectJob: Job? = null

    /** A tapped notification asks to open this session (by id). */
    fun requestOpenSession(id: String?) {
        if (!id.isNullOrEmpty()) _pendingOpenSessionId.value = id
    }

    fun clearPendingOpenSession() {
        _pendingOpenSessionId.value = null
    }

    private fun setConfig(transform: (ConnectionConfig) -> ConnectionConfig) {
        val next = transform(_state.value.config)
        server.config = next
        _state.update { it.copy(config = next, error = null) }
    }

    fun setMode(mode: ConnectionMode) = setConfig { it.copy(mode = mode) }
    fun setRelayURL(v: String) = setConfig { it.copy(relayURL = v) }
    fun setTunnelID(v: String) = setConfig { it.copy(tunnelID = v) }
    fun setToken(v: String) = setConfig { it.copy(token = v) }
    fun setDirectURL(v: String) = setConfig { it.copy(directURL = v) }
    fun setPassword(v: String) = setConfig { it.copy(password = v.ifEmpty { null }) }

    /** Applies a pairing payload (QR, deep link, or manual paste). */
    fun applyPairing(raw: String): Boolean {
        val ok = server.applyPairing(raw)
        if (ok) {
            _state.update { it.copy(config = server.config, error = null) }
        } else {
            _state.update { it.copy(error = "Invalid pairing link") }
        }
        return ok
    }

    /** Applies a pairing deep link and, if valid, immediately connects. */
    fun applyPairingAndConnect(raw: String) {
        if (applyPairing(raw)) connect()
    }

    /** Auto-connects on launch if a complete config was restored. */
    fun autoConnectIfPossible() {
        if (!_state.value.connected && _state.value.config.isComplete) connect()
    }

    fun connect() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        connectJob = viewModelScope.launch {
            runCatching { server.health() }
                .onSuccess { health ->
                    store.save(server.config)
                    registerForPush()
                    startActivityTracking()
                    _state.update {
                        it.copy(loading = false, connected = true, version = health.version)
                    }
                }
                .onFailure { e ->
                    // User-initiated cancel: `cancelConnect()` already cleared the
                    // loading state — rethrow so structured concurrency stays intact
                    // and we don't surface a spurious error.
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(loading = false, connected = false, error = e.message ?: "Connection failed")
                    }
                }
        }
    }

    /** Cancels an in-flight [connect] (e.g. the user tapped Cancel instead of
     *  waiting out a timeout). Leaves the config untouched so they can retry. */
    fun cancelConnect() {
        if (!_state.value.loading) return
        connectJob?.cancel()
        connectJob = null
        _state.update { it.copy(loading = false, error = null) }
    }

    fun disconnect() {
        activityJob?.cancel()
        activityJob = null
        _busySessions.value = emptySet()
        _pendingOpenSessionId.value = null
        _state.update { it.copy(connected = false, version = "") }
    }

    /** Consumes the global event stream app-wide, maintaining [busySessions].
     *  Reconnects with backoff like the session view; idempotent (one job). */
    private fun startActivityTracking() {
        if (activityJob?.isActive == true) return
        activityJob = viewModelScope.launch {
            var backoffMs = 500L
            while (isActive) {
                val stream = server.eventStream() ?: break
                runCatching {
                    stream.frames().collect { data ->
                        backoffMs = 500L
                        ServerEvent.decode(json, data)?.let { applyActivity(it) }
                    }
                }
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    // Internal (not private) so the busy-session reducer is unit-testable without
    // standing up the live global event stream (mirrors iOS ServerConnection.applyActivity).
    internal fun applyActivity(event: ServerEvent) {
        when (event) {
            is ServerEvent.MessageUpdated -> {
                val info = event.info
                if (info is MessageInfo.Assistant) {
                    _busySessions.update {
                        if (info.completed == null) it + event.sessionID else it - event.sessionID
                    }
                }
            }
            // A live delta means the turn is still running — covers a session that
            // was already generating before we subscribed.
            is ServerEvent.PartDelta -> _busySessions.update { it + event.sessionID }
            else -> {}
        }
    }

    /** Registers this device's FCM token with the relay so it can push on idle. */
    private fun registerForPush() {
        studio.eugenezakharov.opencode.push.PushRegistrar.onToken = { token ->
            viewModelScope.launch { server.registerPushToken(token) }
        }
        studio.eugenezakharov.opencode.push.PushRegistrar.lastToken?.let { token ->
            viewModelScope.launch { server.registerPushToken(token) }
        }
        // Fetch the current token (Firebase is only initialized when
        // google-services.json is present, so tolerate its absence).
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    studio.eugenezakharov.opencode.push.PushRegistrar.deliver(token)
                }
        }
    }
}
