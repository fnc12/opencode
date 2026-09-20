package studio.eugenezakharov.opencode

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.ui.session.MessageSkeleton
import studio.eugenezakharov.opencode.ui.session.TypingDots
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Paparazzi renders one frame, so animated placeholders (shimmer skeleton, typing
 * dots) are captured at their deterministic initial state — covering
 * MessageSkeletonKt and TypingDotsKt (both 0% on JVM).
 */
class AnimatedComposablesSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT),
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(12.dp)) { content() }
                }
            }
        }
    }

    @Test fun messageSkeleton() { snap { MessageSkeleton() } }
    @Test fun typingDots() { snap { TypingDots() } }
}
