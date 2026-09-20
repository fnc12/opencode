package studio.eugenezakharov.opencode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.FileEntry
import studio.eugenezakharov.opencode.ui.screens.ProvidersScreen
import studio.eugenezakharov.opencode.ui.session.FilePickerDialog
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Emulator coverage for the two dialog screens (ProvidersScreen, FilePickerDialog)
 * — both 0% on the JVM because Compose Dialogs render in a separate window that
 * Paparazzi's single frame can't capture. Driven by an in-process MockWebServer so
 * the loaded lists + tap interactions run for real, merging into JaCoCo.
 */
@RunWith(AndroidJUnit4::class)
class DialogScreensInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.shutdown() }

    private fun conn() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
    )

    // --- ProvidersScreen -----------------------------------------------------

    @Test fun providersScreenListsAndOpensKeyDialog() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = when {
                    path.startsWith("/provider/auth") -> """{"openai":[{"type":"api"}],"anthropic":[{"type":"api"}]}"""
                    path.startsWith("/config/providers") -> """{"providers":[{"id":"openai","name":"OpenAI","models":{}}]}"""
                    else -> "{}"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
        val connection = conn() // resolve server.url() off the Compose main thread
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProvidersScreen(server = connection, onDismiss = {})
            }
        }
        // The loaded list shows the section header + both api-key providers.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("API KEY PROVIDERS").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("Openai").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Anthropic").fetchSemanticsNodes().isNotEmpty())

        // Tapping a provider opens the API-key entry dialog.
        compose.onNodeWithText("Anthropic").performClick()
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("API Key").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("API Key").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun providersScreenSavesKey() {
        val requests = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                requests.add("${request.method} $path")
                val body = when {
                    path.startsWith("/provider/auth") -> """{"openai":[{"type":"api"}]}"""
                    path.startsWith("/config/providers") -> """{"providers":[]}"""
                    else -> "{}"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
        val connection = conn() // resolve server.url() off the Compose main thread
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProvidersScreen(server = connection, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Openai").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Openai").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("sk-…").performTextInput("sk-secret")
        compose.onNodeWithText("Save").performClick()
        // The Save handler PUTs the key to /auth/openai.
        compose.waitUntil(3_000) { requests.any { it.contains("PUT /auth/openai") } }
        assertTrue(requests.any { it.contains("PUT /auth/openai") })
    }

    // --- FilePickerDialog ----------------------------------------------------

    @Test fun filePickerListsAndPicksFile() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setBody(
                    """[{"name":"src","absolute":"/w/src","type":"directory"},
                        {"name":"README.md","absolute":"/w/README.md","type":"file"}]""",
                )
            }
        }
        server.start()
        val connection = conn() // resolve server.url() off the Compose main thread
        var picked: FileEntry? = null
        var dismissed = false
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                FilePickerDialog(
                    server = connection, startPath = "/w",
                    onPick = { picked = it }, onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty()
        }
        // Both the directory and the file are listed; ".." shows since path != "/".
        assertTrue(compose.onAllNodesWithText("src").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("↰  ..").fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithText("README.md").performClick()
        compose.waitUntil(3_000) { picked != null }
        assertEquals("README.md", picked?.name)
        assertTrue("picking a file dismisses the dialog", dismissed)
    }

    @Test fun filePickerNavigatesIntoDirectory() {
        val visited = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val dir = (request.path ?: "").substringAfter("directory=").substringBefore("&")
                visited.add(dir)
                val body = if (dir.contains("src")) {
                    """[{"name":"main.kt","absolute":"/w/src/main.kt","type":"file"}]"""
                } else {
                    """[{"name":"src","absolute":"/w/src","type":"directory"}]"""
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
        val connection = conn() // resolve server.url() off the Compose main thread
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                FilePickerDialog(server = connection, startPath = "/w", onPick = {}, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("src").fetchSemanticsNodes().isNotEmpty() }
        // Tapping a directory loads its contents (covers load(entry.absolute)).
        compose.onNodeWithText("src").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("main.kt").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("main.kt").fetchSemanticsNodes().isNotEmpty())
    }
}
