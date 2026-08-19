package studio.eugenezakharov.opencode

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import kotlinx.serialization.json.Json
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.ui.screens.ProjectListScreen
import studio.eugenezakharov.opencode.ui.screens.SessionListScreen
import studio.eugenezakharov.opencode.ui.session.MessageDetailScreen
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

    @Test fun sessionListLoading() {
        val project = Json { ignoreUnknownKeys = true }.decodeFromString(
            Project.serializer(),
            """{"id":"p1","worktree":"/Users/me/sources/sqlite_orm","name":"sqlite_orm",
                "time":{"created":1.0,"updated":2.0},"sandboxes":[]}""",
        )
        paparazzi.snapshot {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionListScreen(
                    server = deadServer(), project = project,
                    onSessionClick = {}, onBack = {},
                )
            }
        }
    }

    @Test fun messageDetail() {
        // MessageDetailScreen renders detailBlocks(message) synchronously (no
        // network), so the whole screen — thinking card, answer, tool card — is
        // covered from a fixed message.
        val message = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(
                MessagePart("p0", "s", "m", "assistant", PartContent.Reasoning("Thinking about the parser…"), false, false),
                MessagePart("p1", "s", "m", "assistant", PartContent.Text("Here is the **answer** with `code`."), false, false),
                MessagePart("p2", "s", "m", "assistant",
                    PartContent.Tool(tool = "bash", callID = "c", status = "completed",
                        output = "total 0", input = mapOf("command" to "ls -la")), false, false),
            ),
        )
        paparazzi.snapshot {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                MessageDetailScreen(message = message, onDismiss = {})
            }
        }
    }
}
