package studio.eugenezakharov.opencode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime
import studio.eugenezakharov.opencode.ui.session.ShellScreen
import studio.eugenezakharov.opencode.ui.session.ShellStore
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Instrumented coverage for ShellScreen — a request/response shell (NOT a live
 * PTY): type a command, Run → server.runShell → the output lands in the log.
 * Driven by a MockWebServer that returns a shell tool result.
 */
@RunWith(AndroidJUnit4::class)
class ShellScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        ShellStore.clear("ses_1") // ShellStore persists across tests
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = if ((request.path ?: "").contains("/shell")) {
                    """{"parts":[{"type":"tool","state":{"status":"completed","output":"file1.txt\nfile2.txt"}}]}"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After fun tearDown() { ShellStore.clear("ses_1"); server.shutdown() }

    private fun session() = Session(
        id = "ses_1", projectID = "p", directory = "/w", title = "T", version = "1",
        time = SessionTime(created = 1.0, updated = 2.0),
    )

    @Test fun runsCommandAndShowsOutput() {
        val connection = ServerConnection(
            ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
        )
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ShellScreen(server = connection, session = session(), onDismiss = {})
            }
        }
        // Empty-state hint shows before any command runs.
        assertTrue("empty hint", compose.onAllNodesWithText("Run a shell command", substring = true).fetchSemanticsNodes().isNotEmpty())

        // Type a command + Run → runShell → the log renders "$ ls" + the output.
        compose.onNodeWithTag("shell.field").performTextInput("ls")
        compose.onNodeWithTag("shell.run").performClick()
        compose.waitUntil(6000) { compose.onAllNodesWithText("file1.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("command echo", compose.onAllNodesWithText("$ ls", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("shell output", compose.onAllNodesWithText("file1.txt", substring = true).fetchSemanticsNodes().isNotEmpty())

        // Clear empties the log.
        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("Run a shell command", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("cleared → empty hint back", compose.onAllNodesWithText("Run a shell command", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
