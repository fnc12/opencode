package studio.eugenezakharov.opencode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.ProjectTime
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.ui.screens.ProjectListScreen
import studio.eugenezakharov.opencode.ui.screens.SessionListScreen
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Emulator coverage for the two self-fetch list screens (ProjectListScreen,
 * SessionListScreen) — their loaded/empty/error branches + toolbar actions.
 * Both fetch on compose via LaunchedEffect, so a MockWebServer drives real state.
 *
 * NOTE: the ServerConnection is built on the test thread (server.url() does
 * reverse-DNS, which StrictMode forbids on the Compose main thread).
 */
@RunWith(AndroidJUnit4::class)
class ListScreensInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.shutdown() }

    private fun conn() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
    )

    private fun serve(dispatch: (RecordedRequest) -> Pair<Int, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (code, body) = dispatch(request)
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
        server.start()
    }

    private fun project() = Project(
        id = "p1", worktree = "/Users/me/sqlite_orm", name = "sqlite_orm",
        time = ProjectTime(created = 1.0, updated = 2.0),
    )

    private fun textShown(text: String, timeoutMs: Long = 5_000): Boolean {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    // --- ProjectListScreen ---------------------------------------------------

    @Test fun projectListEmptyState() {
        serve { 200 to "[]" }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProjectListScreen(server = connection, onProjectClick = {}, onSessionCreated = {}, onDisconnect = {})
            }
        }
        assertTrue("empty projects prompt", textShown("No projects yet"))
    }

    @Test fun projectListErrorState() {
        serve { 500 to "boom" }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProjectListScreen(server = connection, onProjectClick = {}, onSessionCreated = {}, onDisconnect = {})
            }
        }
        assertTrue("error header", textShown("Error"))
    }

    @Test fun projectListLoadedShowsRecyclerAndOpensFolderBrowser() {
        serve { req ->
            when {
                (req.path ?: "").startsWith("/project") ->
                    200 to """[{"id":"p1","worktree":"/Users/me/sqlite_orm","name":"sqlite_orm","time":{"created":1,"updated":2},"sandboxes":[]}]"""
                (req.path ?: "").startsWith("/file") -> 200 to "[]"
                else -> 200 to "{}"
            }
        }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProjectListScreen(server = connection, onProjectClick = {}, onSessionCreated = {}, onDisconnect = {})
            }
        }
        // Loaded branch (RecyclerView) composed → the toolbar title is present and
        // the loading spinner is gone.
        assertTrue("projects title", textShown("Projects"))
        // Opening the folder browser mounts OpenFolderBrowser (its own fetch).
        compose.onNodeWithTag("projects.openFolder").performClick()
        assertTrue("folder browser opened", textShown("Open folder"))
    }

    @Test fun projectListOpensProviders() {
        serve { req ->
            when {
                (req.path ?: "").startsWith("/provider/auth") -> 200 to "{}"
                (req.path ?: "").startsWith("/config/providers") -> 200 to """{"providers":[]}"""
                else -> 200 to "[]"
            }
        }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                ProjectListScreen(server = connection, onProjectClick = {}, onSessionCreated = {}, onDisconnect = {})
            }
        }
        textShown("No projects yet")
        compose.onNodeWithTag("projects.providers").performClick()
        assertTrue("providers dialog opened", textShown("Providers"))
    }

    // --- SessionListScreen ---------------------------------------------------

    @Test fun sessionListLoadedShowsRecycler() {
        serve {
            200 to """[{"id":"ses_1","projectID":"p1","directory":"/Users/me/sqlite_orm","title":"Fix parser","version":"1","time":{"created":1,"updated":2}}]"""
        }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionListScreen(server = connection, project = project(), onSessionClick = {}, onBack = {})
            }
        }
        // Loaded branch composed (RecyclerView) → the project title shows in the bar.
        assertTrue("session screen title", textShown("sqlite_orm"))
    }

    @Test fun sessionListEmptyAndNewSession() {
        var created: Session? = null
        serve { req ->
            when {
                req.method == "POST" && (req.path ?: "").startsWith("/session") ->
                    200 to """{"id":"ses_new","projectID":"p1","directory":"/Users/me/sqlite_orm","title":"","version":"1","time":{"created":9,"updated":9}}"""
                else -> 200 to "[]" // empty session list
            }
        }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionListScreen(server = connection, project = project(), onSessionClick = { created = it }, onBack = {})
            }
        }
        assertTrue("empty sessions prompt", textShown("No sessions yet"))
        // Tapping "new" creates a session → onSessionClick fires.
        compose.onNodeWithTag("sessions.new").performClick()
        compose.waitUntil(5_000) { created != null }
        assertTrue("new session created", created?.id == "ses_new")
    }

    @Test fun sessionListErrorState() {
        serve { 500 to "boom" }
        val connection = conn()
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionListScreen(server = connection, project = project(), onSessionClick = {}, onBack = {})
            }
        }
        assertTrue("error header", textShown("Error"))
    }
}
