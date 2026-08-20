package studio.eugenezakharov.opencode

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
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
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.ui.AppNav
import studio.eugenezakharov.opencode.ui.AppViewModel
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Integration coverage for AppNav — the root nav host. Drives all its `when`
 * branches: disconnected → ConnectScreen; connected → ProjectListScreen; and the
 * push deep-link path (requestOpenSession → resolve session + project → the
 * session destination), which exercises the deep-link LaunchedEffect without
 * needing RecyclerView taps.
 */
@RunWith(AndroidJUnit4::class)
class AppNavInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        // Navigating into a session here must not leave an infinite SSE reconnect
        // loop running (its viewModelScope is never cleared) — that leaks coroutines
        // across later tests in the same process.
        studio.eugenezakharov.opencode.ui.UiTestFlags.disableStream = true
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = when {
                    path.contains("/global/health") -> """{"healthy":true,"version":"1"}"""
                    path.startsWith("/project") ->
                        """[{"id":"p1","worktree":"/w","name":"proj","time":{"created":1,"updated":2},"sandboxes":[]}]"""
                    path.startsWith("/session/ses_1") && !path.contains("/message") ->
                        """{"id":"ses_1","projectID":"p1","directory":"/w","title":"Deep linked","version":"1","time":{"created":1,"updated":2}}"""
                    request.method == "GET" && path.startsWith("/session") ->
                        """[{"id":"ses_row","projectID":"p1","directory":"/w","title":"Row session","version":"1","time":{"created":1,"updated":2}}]"""
                    path.contains("/global/event") -> "" // no live events in this test
                    else -> "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After fun tearDown() {
        studio.eugenezakharov.opencode.ui.UiTestFlags.disableStream = false
        server.shutdown()
    }

    private fun textShown(text: String, timeoutMs: Long = 5_000): Boolean {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun navFlowsFromConnectToProjectsToDeepLinkedSession() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                AppNav(appViewModel = vm)
            }
        }
        // Disconnected → the Connect screen branch renders.
        assertTrue("starts on Connect", textShown("Relay") || textShown("Connect"))

        // Point at the mock server and connect → the connected home (Projects).
        vm.setMode(ConnectionMode.DIRECT)
        vm.setDirectURL(server.url("/").toString().trimEnd('/'))
        vm.connect()
        assertTrue("connected → Projects home", textShown("Projects"))

        // Push deep-link: request a session by id → the deep-link effect resolves it
        // (getSession + projects) and navigates to the session destination.
        vm.requestOpenSession("ses_1")
        assertTrue("deep-link navigates to the session", textShown("Deep linked"))
    }
}
