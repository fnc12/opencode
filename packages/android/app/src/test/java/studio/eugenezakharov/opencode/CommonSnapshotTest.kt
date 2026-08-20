package studio.eugenezakharov.opencode

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.ui.screens.CenteredMessage
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/** Paparazzi goldens for the shared empty/error placeholder (Common.kt). */
class CommonSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT),
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    @Test fun plainMessage() {
        snap { CenteredMessage(title = "No projects yet", description = "Open a folder to start.") }
    }

    @Test fun withRetryAction() {
        snap {
            CenteredMessage(
                title = "Couldn't load",
                description = "The server took too long to respond.",
                actionLabel = "Retry",
                onAction = {},
            )
        }
    }
}
