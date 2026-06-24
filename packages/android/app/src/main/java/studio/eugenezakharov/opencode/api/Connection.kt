package studio.eugenezakharov.opencode.api

import android.net.Uri

/** How the app reaches the OpenCode server. */
enum class ConnectionMode {
    /** Through the OpenCode Remote relay (server is behind NAT). */
    RELAY,

    /** Straight to a reachable OpenCode server (LAN / dev). */
    DIRECT,
}

/**
 * Everything needed to connect. Mirrors the iOS `ConnectionConfig`.
 *
 * For relay mode the base URL is the per-tunnel proxy path on the relay
 * (`relayURL/t/{tunnelID}`); for direct mode it is the server URL.
 */
data class ConnectionConfig(
    val mode: ConnectionMode = ConnectionMode.RELAY,
    // relay mode
    val relayURL: String = "",
    val tunnelID: String = "",
    val token: String = "",
    // direct mode
    val directURL: String = "",
    val password: String? = null,
) {
    val baseURL: String
        get() = when (mode) {
            ConnectionMode.RELAY -> relayURL.trimmedSlashes() + "/t/" + tunnelID
            ConnectionMode.DIRECT -> directURL.trimmedSlashes()
        }

    /** Whether the user has filled in enough to attempt a connection. */
    val isComplete: Boolean
        get() = when (mode) {
            ConnectionMode.RELAY ->
                relayURL.isNotEmpty() && tunnelID.isNotEmpty() && token.isNotEmpty()
            ConnectionMode.DIRECT -> directURL.isNotEmpty()
        }

    companion object {
        /**
         * Parses a pairing payload produced by the connector, accepting either an
         * `opencode://pair?relay=…&tunnel=…&token=…` URL (also used for QR codes
         * and deep links) or a bare `relay=…&tunnel=…&token=…` query string.
         */
        fun fromPairing(raw: String): ConnectionConfig? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            var query = trimmed
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            if (uri != null && uri.scheme != null) {
                val isPair = uri.host == "pair" || (uri.path?.contains("pair") == true)
                if (!isPair) return null
                query = uri.encodedQuery ?: ""
            }

            val items = mutableMapOf<String, String>()
            for (pair in query.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size != 2) continue
                val value = Uri.decode(kv[1])
                items[kv[0]] = value
            }

            val relay = items["relay"]
            val tunnel = items["tunnel"]
            val token = items["token"]
            if (relay.isNullOrEmpty() || tunnel.isNullOrEmpty() || token.isNullOrEmpty()) {
                return null
            }

            return ConnectionConfig(
                mode = ConnectionMode.RELAY,
                relayURL = relay,
                tunnelID = tunnel,
                token = token,
            )
        }
    }
}

/** Trims surrounding whitespace and any trailing slashes. */
fun String.trimmedSlashes(): String {
    var s = trim()
    while (s.endsWith("/")) s = s.dropLast(1)
    return s
}
