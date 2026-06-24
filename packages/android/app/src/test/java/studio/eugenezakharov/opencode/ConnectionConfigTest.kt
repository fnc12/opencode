package studio.eugenezakharov.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode

/** Pairing-payload parsing and base-URL derivation. Mirrors iOS ConnectionConfig. */
class ConnectionConfigTest {
    @Test
    fun parsesPairUrl() {
        val cfg = ConnectionConfig.fromPairing("opencode://pair?relay=https://r.example.com&tunnel=t1&token=secret")
        requireNotNull(cfg)
        assertEquals(ConnectionMode.RELAY, cfg.mode)
        assertEquals("https://r.example.com", cfg.relayURL)
        assertEquals("t1", cfg.tunnelID)
        assertEquals("secret", cfg.token)
    }

    @Test
    fun parsesBareQueryString() {
        val cfg = ConnectionConfig.fromPairing("relay=https://r.example.com&tunnel=t1&token=secret")
        requireNotNull(cfg)
        assertEquals("t1", cfg.tunnelID)
    }

    @Test
    fun decodesPercentEncoding() {
        val cfg = ConnectionConfig.fromPairing("relay=https%3A%2F%2Fr.example.com&tunnel=t1&token=a%20b")
        requireNotNull(cfg)
        assertEquals("https://r.example.com", cfg.relayURL)
        assertEquals("a b", cfg.token)
    }

    @Test
    fun rejectsMissingFields() {
        assertNull(ConnectionConfig.fromPairing("relay=x&tunnel=y"))
        assertNull(ConnectionConfig.fromPairing(""))
    }

    @Test
    fun rejectsNonPairScheme() {
        assertNull(ConnectionConfig.fromPairing("https://example.com/?relay=x&tunnel=y&token=z"))
    }

    @Test
    fun baseUrlRelayAppendsTunnelPath() {
        val cfg = ConnectionConfig(mode = ConnectionMode.RELAY, relayURL = "https://r.example.com/", tunnelID = "t1", token = "x")
        assertEquals("https://r.example.com/t/t1", cfg.baseURL)
        assertTrue(cfg.isComplete)
    }

    @Test
    fun baseUrlDirectTrimsSlashes() {
        val cfg = ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = "http://10.0.2.2:4096/")
        assertEquals("http://10.0.2.2:4096", cfg.baseURL)
        assertTrue(cfg.isComplete)
    }
}
