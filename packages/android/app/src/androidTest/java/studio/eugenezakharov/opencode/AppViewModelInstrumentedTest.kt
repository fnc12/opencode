package studio.eugenezakharov.opencode

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.ui.AppViewModel
import studio.eugenezakharov.opencode.ui.screens.ConnectScreen
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Instrumented coverage for AppViewModel (Context-bound: AndroidViewModel +
 * EncryptedSharedPreferences) and the Connect screen it drives — both 0% on the
 * JVM side. Uses the real Application from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class AppViewModelInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun vm(): AppViewModel =
        AppViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test fun configSettersUpdateState() {
        val vm = vm()
        vm.setMode(ConnectionMode.DIRECT)
        vm.setDirectURL("http://host:4096")
        vm.setPassword("secret")
        assertEquals(ConnectionMode.DIRECT, vm.state.value.config.mode)
        assertEquals("http://host:4096", vm.state.value.config.directURL)
        assertEquals("secret", vm.state.value.config.password)

        vm.setMode(ConnectionMode.RELAY)
        vm.setRelayURL("https://relay.example")
        vm.setTunnelID("tun_9")
        vm.setToken("tok_9")
        assertEquals(ConnectionMode.RELAY, vm.state.value.config.mode)
        assertEquals("tun_9", vm.state.value.config.tunnelID)
    }

    @Test fun applyPairingParsesLink() {
        val vm = vm()
        val ok = vm.applyPairing("opencode://pair?relay=https://r.example&tunnel=tun_1&token=tok_1")
        assertTrue("a valid pair link must apply", ok)
        assertEquals("tun_1", vm.state.value.config.tunnelID)
        assertEquals(ConnectionMode.RELAY, vm.state.value.config.mode)
    }

    @Test fun requestAndClearPendingOpenSession() {
        val vm = vm()
        vm.requestOpenSession("ses_9")
        assertEquals("ses_9", vm.pendingOpenSessionId.value)
        vm.clearPendingOpenSession()
        org.junit.Assert.assertNull(vm.pendingOpenSessionId.value)
    }

    @Test fun disconnectClearsConnectedState() {
        val vm = vm()
        vm.disconnect()
        org.junit.Assert.assertFalse(vm.state.value.connected)
    }

    @Test fun connectSucceedsAgainstHealthyServer() {
        val mock = okhttp3.mockwebserver.MockWebServer()
        mock.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"healthy":true,"version":"1.2.3"}"""))
        mock.start()
        try {
            val vm = vm()
            vm.setMode(ConnectionMode.DIRECT)
            vm.setDirectURL(mock.url("/").toString().trimEnd('/'))
            vm.connect()
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline && !vm.state.value.connected && vm.state.value.error == null) {
                Thread.sleep(20)
            }
            assertTrue("connect against a healthy server must succeed", vm.state.value.connected)
        } finally {
            mock.shutdown()
        }
    }

    @Test fun connectScreenRenders() {
        val vm = vm()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ConnectScreen(state = vm.state.value, viewModel = vm)
            }
        }
        // The Connect screen shows its mode toggle / connect affordance.
        val hasConnect = compose.onAllNodesWithText("Connect", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Direct", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Relay", substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("Connect screen must render its controls", hasConnect)
    }
}
