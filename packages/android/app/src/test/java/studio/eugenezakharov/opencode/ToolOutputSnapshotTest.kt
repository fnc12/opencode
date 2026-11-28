package studio.eugenezakharov.opencode

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.TodoItem
import studio.eugenezakharov.opencode.ui.session.CodeText
import studio.eugenezakharov.opencode.ui.session.DiffText
import studio.eugenezakharov.opencode.ui.session.TodoChecklist
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Paparazzi Compose goldens for the tool-output renderers on the message-detail
 * screen (`ToolOutput.kt` — was 18.8%). Static, data-driven composables: a
 * colored unified diff, a syntax-highlighted read, and a todo checklist. Dark
 * theme, fixed (non-dynamic) colors so the golden is deterministic.
 */
class ToolOutputSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
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

    @Test fun diff() {
        snap {
            DiffText(
                "--- a/main.c\n+++ b/main.c\n@@ -1,4 +1,4 @@\n" +
                    " int main() {\n-  return 0;\n+  return 1;\n }",
            )
        }
    }

    @Test fun todoChecklist() {
        snap {
            TodoChecklist(
                listOf(
                    TodoItem(content = "Parse the header", status = "completed", priority = "high"),
                    TodoItem(content = "Rename the node", status = "in_progress", priority = "high"),
                    TodoItem(content = "Run the suite", status = "pending", priority = "medium"),
                ),
            )
        }
    }

    @Test fun codeText() {
        snap { CodeText("fun main() {\n    println(\"hello\")\n}\n", "Main.kt") }
    }
}
