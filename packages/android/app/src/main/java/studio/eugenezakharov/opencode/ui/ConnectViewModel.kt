package studio.eugenezakharov.opencode.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection

data class ConnectUiState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connected: Boolean = false,
    val version: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

class ConnectViewModel(
    private val server: ServerConnection = ServerConnection(),
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState(config = server.config))
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private fun setConfig(transform: (ConnectionConfig) -> ConnectionConfig) {
        _state.update { it.copy(config = transform(it.config), error = null) }
        server.config = _state.value.config
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

    fun connect() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { server.health() }
                .onSuccess { health ->
                    _state.update {
                        it.copy(
                            loading = false,
                            connected = true,
                            version = health.version,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, connected = false, error = e.message ?: "Connection failed")
                    }
                }
        }
    }
}
