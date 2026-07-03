package studio.eugenezakharov.opencode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ConnectionStore
import studio.eugenezakharov.opencode.api.ServerConnection

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
        viewModelScope.launch {
            runCatching { server.health() }
                .onSuccess { health ->
                    store.save(server.config)
                    registerForPush()
                    _state.update {
                        it.copy(loading = false, connected = true, version = health.version)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, connected = false, error = e.message ?: "Connection failed")
                    }
                }
        }
    }

    fun disconnect() {
        _state.update { it.copy(connected = false, version = "") }
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
