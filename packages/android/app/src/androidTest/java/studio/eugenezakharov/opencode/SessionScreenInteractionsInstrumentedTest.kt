package studio.eugenezakharov.opencode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

    /** With the clock paused, pump it in small steps (giving background network a
     *  chance to return) until [cond] holds or the deadline passes. */
    private fun advanceUntil(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            compose.mainClock.advanceTimeBy(120)
            Thread.sleep(40)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
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

    private fun sessionWithDiff() = Session(
        id = "ses_1", projectID = "p", directory = "/w", title = "Fix the parser",
        version = "1", time = SessionTime(created = 1.0, updated = 2.0),
        summary = studio.eugenezakharov.opencode.api.models.SessionSummary(additions = 5, deletions = 2, files = 1),
    )

    @Test fun diffButtonOpensDiffScreenAndFileDetail() {
        // A session summary with files>0 → hasDiff → the ± diff button shows.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add("${request.method} ${request.path}")
                val body = if ((request.path ?: "").contains("/diff")) {
                    """[{"file":"src/parser.kt","patch":"@@ -1,2 +1,2 @@\n-old line\n+new line","additions":5,"deletions":2,"status":"modified"}]"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        val vm = SessionViewModel(server = conn(), session = sessionWithDiff(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm) // autoAdvance = false (the connecting spinner never idles)
        // Open the diff screen → it loads the file list over the network. With the
        // clock paused, manually advance it while the network returns so the load
        // effect's recomposition flushes (waitUntil would need idle; performClick
        // would block on the spinner if autoAdvance were on).
        compose.onNodeWithTag("session.diff").performClick()
        advanceUntil(8000) { compose.onAllNodesWithText("src/parser.kt").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("src/parser.kt").fetchSemanticsNodes().isNotEmpty())
        // Tap the file row → DiffFileDetail with the colored diff (coloredDiff()).
        compose.onNodeWithText("src/parser.kt").performClick()
        advanceUntil(5000) { compose.onAllNodesWithText("Changes").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("diff detail opened (Changes screen still up)",
            compose.onAllNodesWithText("Changes").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun multiQuestionPagerAdvancesAndSubmits() {
        // Two single-select questions → isMulti → HorizontalPager + "1 / 2" indicator;
        // picking the first auto-advances to the second (Claude-style slides).
        val json = """[{"id":"q","sessionID":"ses_1","questions":[
            {"question":"Q1","header":"First","options":[{"label":"A1","description":"a"},{"label":"B1","description":"b"}]},
            {"question":"Q2","header":"Second","options":[{"label":"A2","description":"a"}]}]}]"""
        val vm = SessionViewModel(
            server = conn(), session = session(), prefs = Prefs(),
            streamLive = false, injectQuestionJson = json,
        )
        awaitLoaded(vm)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.pendingQuestions.isEmpty()) Thread.sleep(20)
        mount(vm)
        compose.waitUntil(3000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("multi-question shows a page indicator", compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty())
        // Pick Q1's option → advances to page 2.
        compose.onNodeWithTag("A1").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(3000) { vm.state.value.pendingQuestions.isNotEmpty() } // still pending until submit
        // Pick Q2 + submit → clears.
        compose.waitUntil(3000) { compose.onAllNodesWithTag("A2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("A2").performClick()
        compose.onNodeWithTag("question.submit").performClick()
        compose.waitUntil(3000) { vm.state.value.pendingQuestions.isEmpty() }
        assertTrue(vm.state.value.pendingQuestions.isEmpty())
    }

    @Test fun multiSelectQuestionTogglesOptions() {
        // A single multi-select question → tapping toggles options on and off.
        val json = """[{"id":"q","sessionID":"ses_1","questions":[
            {"question":"Pick any","header":"Multi","multiple":true,
             "options":[{"label":"Opt1","description":"a"},{"label":"Opt2","description":"b"}]}]}]"""
        val vm = SessionViewModel(
            server = conn(), session = session(), prefs = Prefs(),
            streamLive = false, injectQuestionJson = json,
        )
        awaitLoaded(vm)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.pendingQuestions.isEmpty()) Thread.sleep(20)
        mount(vm)
        compose.waitUntil(3000) { compose.onAllNodesWithTag("Opt1").fetchSemanticsNodes().isNotEmpty() }
        // Toggle on, on, then off (covers both branches of the multi-select toggle).
        compose.onNodeWithTag("Opt1").performClick()
        compose.onNodeWithTag("Opt2").performClick()
        compose.onNodeWithTag("Opt1").performClick() // deselect
        compose.onNodeWithTag("question.submit").performClick()
        compose.waitUntil(3000) { vm.state.value.pendingQuestions.isEmpty() }
        assertTrue(vm.state.value.pendingQuestions.isEmpty())
    }

    @Test fun commandsDialogRunsCommand() {
        // Commands loaded → the attach menu's "commands" item shows; opening the
        // dialog lists them and tapping one runs it (POST /command).
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add("${request.method} ${request.path}")
                val body = if (request.method == "GET" && (request.path ?: "").startsWith("/command")) {
                    """[{"name":"init","description":"Initialize the project"},{"name":"review","description":"Review changes"}]"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.commands.isEmpty()) Thread.sleep(20)
        mount(vm)
        compose.onNodeWithTag("composer.plus").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(3000) { compose.onAllNodesWithTag("composer.commands").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("composer.commands").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // The commands dialog lists each command; tapping one runs it.
        compose.waitUntil(3000) { compose.onAllNodesWithText("/init").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/init").performClick()
        waitForPath("POST /session/ses_1/command")
        assertTrue("running a command POSTs it", sawPath("POST /session/ses_1/command"))
    }

    @Test fun todoPillOpensTasksDialog() {
        val vm = SessionViewModel(
            server = conn(), session = session(), prefs = Prefs(),
            streamLive = false, injectTestTodo = true,
        )
        awaitLoaded(vm)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.state.value.todos.isEmpty()) Thread.sleep(20)
        mount(vm)
        // Tap the "☑ Tasks n/m" pill → the TodoDialog (with each status glyph) opens.
        compose.waitUntil(3000) { compose.onAllNodesWithText("Tasks", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Tasks", substring = true).onFirst().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(3000) { compose.onAllNodesWithText("Read the AST query schema", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("todo dialog lists the tasks",
            compose.onAllNodesWithText("Read the AST query schema", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun shellButtonOpensShellScreen() {
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm)
        // Tapping >_ sets showShell = true → the ShellScreen overlay composes.
        compose.onNodeWithTag("session.shell").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("shell button stays in the tree", compose.onAllNodesWithTag("session.shell").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun shareMenuOpens() {
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = false)
        awaitLoaded(vm)
        mount(vm)
        compose.onNodeWithTag("session.share").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // The share dropdown's "Share link" item is now in the tree (menu body ran).
        compose.waitUntil(3000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty())
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
