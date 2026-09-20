package studio.eugenezakharov.opencode

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.ui.AppViewModel
import studio.eugenezakharov.opencode.ui.screens.ConnectScreen
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Covers the QR-pairing scan flow on ConnectScreen: tapping "Scan pairing QR"
 * launches the ZXing capture activity and its result callback applies the scanned
 * pairing link. Espresso Intents stubs the scan Activity, returning a SCAN_RESULT
 * extra so no camera launches and the callback runs deterministically.
 */
@RunWith(AndroidJUnit4::class)
class QrScanInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        Intents.init()
        // The scan returns a pairing link via the ZXing SCAN_RESULT extra.
        val result = Intent().putExtra("SCAN_RESULT", "opencode://pair?relay=https://r.example&tunnel=tun_qr&token=tok_qr")
        Intents.intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, result))
    }

    @After fun tearDown() { Intents.release() }

    @Test fun scanQrAppliesPairing() {
        val vm = AppViewModel(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        vm.setMode(ConnectionMode.RELAY) // the Scan QR button lives in relay mode
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                val state by vm.state.collectAsState()
                ConnectScreen(state = state, viewModel = vm)
            }
        }
        compose.onNodeWithTag("connect.scanQr").performClick()
        // The stubbed scan result flows into applyPairingAndConnect → config updates.
        compose.waitUntil(5000) { vm.state.value.config.tunnelID == "tun_qr" }
        assertEquals("tun_qr", vm.state.value.config.tunnelID)
    }
}
