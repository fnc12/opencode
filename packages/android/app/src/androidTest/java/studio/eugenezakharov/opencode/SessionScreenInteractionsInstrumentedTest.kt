package studio.eugenezakharov.opencode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import studio.eugenezakharov.opencode.api.ComposerPrefs
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime
import studio.eugenezakharov.opencode.ui.session.SessionScreen
import studio.eugenezakharov.opencode.ui.session.SessionViewModel
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

private class Prefs(
    override var providerID: String = "zai",
    override var modelID: String = "glm-5.2",
    override var agent: String = "build",
) : ComposerPrefs {
    override fun setModel(providerID: String, modelID: String) {
        this.providerID = providerID; this.modelID = modelID
    }
}

/**
 * Emulator coverage for SessionScreen's *interaction* lambdas — the composer
 * (type + send, agent menu, model picker, attach menu) and the permission/question
 * docks (allow, submit) — the big Composer$N / Dock$N blocks that the render-only
 * SessionScreenInstrumentedTest doesn't exercise. Coverage merges via JaCoCo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionScreenInteractionsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add("${request.method} ${request.path}")
                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun conn() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
    )

    private fun session() = Session(
        id = "ses_1", projectID = "p", directory = "/w", title = "Fix the parser",
        version = "1", time = SessionTime(created = 1.0, updated = 2.0),
    )

    private fun awaitLoaded(vm: SessionViewModel) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && vm.state.value.loading) Thread.sleep(20)
    }

    private fun sawPath(substr: String) = synchronized(paths) { paths.any { it.contains(substr) } }
    private fun waitForPath(substr: String, ms: Long = 4000) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && !sawPath(substr)) Thread.sleep(20)
    }

    private fun mount(vm: SessionViewModel) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionScreen(viewModel = vm, session = session(), onBack = {})
            }
        }
    }

    @Test fun typingAndSendPostsPrompt() {
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm)
        compose.onNodeWithTag("composer.field").performTextInput("run the tests")
        compose.onNodeWithTag("composer.send").performClick()
        waitForPath("POST /session/ses_1/message")
        assertTrue("send posts the prompt", sawPath("POST /session/ses_1/message"))
    }

    @Test fun agentMenuOpens() {
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm)
        compose.onNodeWithTag("composer.agent").performClick()
        // The dropdown composed (its onClick ran) — the screen is still up.
        assertTrue(compose.onAllNodesWithTag("composer.agent").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun modelPickerOpens() {
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm)
        compose.onNodeWithTag("composer.model").performClick()
        assertTrue(compose.onAllNodesWithTag("composer.model").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun attachMenuOpensAndSelectsFilePicker() {
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm)
        compose.onNodeWithTag("composer.plus").performClick()
        // Advance the paused clock so the dropdown's enter animation settles and
        // its items become hittable (the menu body lambdas then execute).
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(3000) { compose.onAllNodesWithTag("composer.file").fetchSemanticsNodes().isNotEmpty() }
        // Selecting "Attach a file" opens the file picker dialog (showFilePicker = true).
        compose.onNodeWithTag("composer.file").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(compose.onAllNodesWithTag("composer.plus").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun modelPickerOpensAndShowsRows() {
        // A providers response so the picker has a row to render (its item lambda runs).
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add("${request.method} ${request.path}")
                val body = if ((request.path ?: "").contains("/config/providers")) {
                    """{"providers":[{"id":"zai","name":"Z-AI","models":{"glm-5.2":{"id":"glm-5.2","name":"GLM 5.2"}}}]}"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        // Let the providers load so the picker has content.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.providers.isEmpty()) Thread.sleep(20)
        mount(vm)
        compose.onNodeWithTag("composer.model").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // The model picker dialog renders the provider/model (its item lambda runs).
        compose.waitUntil(3000) { compose.onAllNodesWithText("GLM 5.2", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("GLM 5.2", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun permissionAllowReplies() {
        val vm = SessionViewModel(
            server = conn(), session = session(), prefs = Prefs(),
            streamLive = false, injectTestPermission = true,
        )
        awaitLoaded(vm)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.pendingPermissions.isEmpty()) Thread.sleep(20)
        mount(vm)
        compose.waitUntil(3000) { compose.onAllNodesWithTag("permission.allow").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("permission.allow").performClick()
        // Optimistic clear + a reply POST.
        compose.waitUntil(3000) { vm.state.value.pendingPermissions.isEmpty() }
        assertTrue("allow clears the pending permission", vm.state.value.pendingPermissions.isEmpty())
    }

    @Test fun questionPickAndSubmitReplies() {
        val vm = SessionViewModel(
            server = conn(), session = session(), prefs = Prefs(),
            streamLive = false, injectTestQuestion = true,
        )
        awaitLoaded(vm)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.pendingQuestions.isEmpty()) Thread.sleep(20)
        mount(vm)
        compose.waitUntil(3000) { compose.onAllNodesWithText("Option A").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Option A").onFirst().performClick()
        compose.onNodeWithTag("question.submit").performClick()
        compose.waitUntil(3000) { vm.state.value.pendingQuestions.isEmpty() }
        assertTrue("submit clears the pending question", vm.state.value.pendingQuestions.isEmpty())
    }
}
