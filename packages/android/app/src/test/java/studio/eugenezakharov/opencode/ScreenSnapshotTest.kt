package studio.eugenezakharov.opencode

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.ui.screens.ProjectListScreen
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Paparazzi goldens for full Compose screens in their INITIAL (loading) state —
 * Paparazzi renders one frame, so the screen's async LaunchedEffect (network
 * fetch) hasn't resolved and the loading UI shows. This still executes the
 * screen's Scaffold / top bar / loading branch, which was 0%. Full-device
 * rendering (Scaffold fills the screen). Points at an unreachable server so no
 * network is actually needed.
 */
class ScreenSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT),
    )

    private fun deadServer() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = "http://127.0.0.1:1"),
    )

    @Test fun projectListLoading() {
        paparazzi.snapshot {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProjectListScreen(
                    server = deadServer(),
                    onProjectClick = {},
                    onSessionCreated = {},
                    onDisconnect = {},
                )
            }
        }
    }
}
