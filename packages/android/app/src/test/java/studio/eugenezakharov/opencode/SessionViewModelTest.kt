package studio.eugenezakharov.opencode

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.eugenezakharov.opencode.api.ComposerPrefs
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime
import studio.eugenezakharov.opencode.ui.session.SessionViewModel

/**
 * [SessionViewModel] logic driven against a fake server (MockWebServer), with the
 * live SSE loop disabled (`streamLive = false`) so the initial load completes.
 * Uses an unconfined main dispatcher + real polling (`waitFor`) because
 * ServerConnection does its IO on `Dispatchers.IO`, which escapes virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private lateinit var server: MockWebServer

    private val recordedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        recordedPaths.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedPaths.add(request.path ?: "")
                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }
        server.start()
    }

    private fun sawPath(substr: String) = synchronized(recordedPaths) { recordedPaths.any { it.contains(substr) } }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    private fun connection() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
    )

    private fun session() = Session(
        id = "ses_1", projectID = "p", directory = "/w", title = "T", version = "1",
        time = SessionTime(created = 1.0, updated = 2.0),
    )

    private fun viewModel(prefs: ComposerPrefs = FakeComposerPrefs()) = SessionViewModel(
        server = connection(), session = session(), prefs = prefs, streamLive = false,
    )

    @Test fun initialStateReflectsPrefs() {
        // The state flow is seeded from prefs synchronously in the constructor,
        // before any async load runs.
        val vm = viewModel(FakeComposerPrefs(providerID = "zai", modelID = "glm-5.2", agent = "plan"))
        assertEquals("zai", vm.state.value.providerID)
        assertEquals("glm-5.2", vm.state.value.modelID)
        assertEquals("plan", vm.state.value.agentName)
    }

    @Test fun loadClearsLoadingWithEmptyHistory() {
        val vm = viewModel()
        waitFor { !vm.state.value.loading }
        assertFalse(vm.state.value.loading)
        assertEquals(0, vm.state.value.messages.size)
    }

    @Test fun selectAgentPersistsAndUpdatesState() {
        val prefs = FakeComposerPrefs()
        val vm = viewModel(prefs)
        waitFor { !vm.state.value.loading }
        vm.selectAgent("plan")
        assertEquals("plan", prefs.agent)
        assertEquals("plan", vm.state.value.agentName)
    }

    @Test fun selectModelPersistsToPrefs() {
        val prefs = FakeComposerPrefs()
        val vm = viewModel(prefs)
        waitFor { !vm.state.value.loading }
        vm.selectModel("zai", "glm-4.7-flash")
        assertEquals("zai", prefs.providerID)
        assertEquals("glm-4.7-flash", prefs.modelID)
        assertEquals("zai", vm.state.value.providerID)
        assertEquals("glm-4.7-flash", vm.state.value.modelID)
    }

    @Test fun sendPostsPromptAndClearsComposer() {
        val vm = viewModel(FakeComposerPrefs(providerID = "zai", modelID = "glm-5.2"))
        waitFor { !vm.state.value.loading }
        var cleared = false
        vm.send("hello there", onCleared = { cleared = true }, restore = {})
        assertTrue("composer must be cleared optimistically", cleared)
        waitFor { sawPath("/session/ses_1/message") && !vm.state.value.sending }
        assertFalse(vm.state.value.sending)
    }

    @Test fun sendIgnoredWithoutModel() {
        // No model selected → send is a no-op (the server has no default model).
        val vm = viewModel(FakeComposerPrefs(modelID = ""))
        waitFor { !vm.state.value.loading }
        var cleared = false
        vm.send("hello", onCleared = { cleared = true }, restore = {})
        assertFalse("send must be ignored when no model is chosen", cleared)
    }

    @Test fun abortCallsServer() {
        val vm = viewModel(FakeComposerPrefs(modelID = "m"))
        waitFor { !vm.state.value.loading }
        vm.abort()
        waitFor { sawPath("/session/ses_1/abort") }
        assertTrue(sawPath("/session/ses_1/abort"))
    }

    @Test fun replyPermissionClearsPending() {
        // injectTestPermission seeds a synthetic pending permission during load.
        val vm = SessionViewModel(
            server = connection(), session = session(),
            prefs = FakeComposerPrefs(), streamLive = false, injectTestPermission = true,
        )
        waitFor { vm.state.value.pendingPermissions.isNotEmpty() }
        val req = vm.state.value.pendingPermissions.first()
        vm.replyPermission(req, "once")
        assertTrue("answering clears it locally right away", vm.state.value.pendingPermissions.isEmpty())
    }

    @Test fun replyQuestionClearsPending() {
        val vm = SessionViewModel(
            server = connection(), session = session(),
            prefs = FakeComposerPrefs(), streamLive = false, injectTestQuestion = true,
        )
        waitFor { vm.state.value.pendingQuestions.isNotEmpty() }
        vm.replyQuestion(vm.state.value.pendingQuestions.first(), listOf(listOf("Option A")))
        assertTrue(vm.state.value.pendingQuestions.isEmpty())
    }

    @Test fun rejectQuestionClearsPending() {
        val vm = SessionViewModel(
            server = connection(), session = session(),
            prefs = FakeComposerPrefs(), streamLive = false, injectTestQuestion = true,
        )
        waitFor { vm.state.value.pendingQuestions.isNotEmpty() }
        vm.rejectQuestion(vm.state.value.pendingQuestions.first())
        assertTrue(vm.state.value.pendingQuestions.isEmpty())
    }

    @Test fun revertHitsServer() {
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        vm.revert("msg_9")
        waitFor { sawPath("/session/ses_1/revert") }
        assertTrue(sawPath("/session/ses_1/revert"))
    }

    @Test fun restoreHitsServer() {
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        vm.restore()
        waitFor { sawPath("/session/ses_1/unrevert") }
        assertTrue(sawPath("/session/ses_1/unrevert"))
    }

    @Test fun runCommandHitsServer() {
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        vm.runCommand("test")
        waitFor { sawPath("/session/ses_1/command") }
        assertTrue(sawPath("/session/ses_1/command"))
    }

    @Test fun shareHitsServer() {
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        vm.share(onLink = {})
        waitFor { sawPath("/session/ses_1/share") }
        assertTrue(sawPath("/session/ses_1/share"))
    }

    @Test fun loadOlderPagesWithCursor() {
        // A dispatcher that hands out one older page: the initial message request
        // (no `before=`) returns a next-cursor; the paged request (with `before=`)
        // returns the last page (no cursor).
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                recordedPaths.add(path)
                return when {
                    path.contains("/message") && !path.contains("before=") ->
                        MockResponse().setResponseCode(200).setBody("[]").setHeader("X-Next-Cursor", "cur_1")
                    else -> MockResponse().setResponseCode(200).setBody("[]")
                }
            }
        }
        val vm = viewModel()
        waitFor { !vm.state.value.loading }
        vm.loadOlder()
        waitFor { sawPath("before=cur_1") }
        assertTrue("loadOlder must fetch the next page with the cursor", sawPath("before=cur_1"))
    }

    @Test fun unshareHitsServerAndClearsUrl() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                recordedPaths.add(path)
                val body = if (path.contains("/share")) {
                    """{"id":"ses_1","projectID":"p","directory":"/w","title":"T","version":"1","time":{"created":1,"updated":2}}"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        vm.unshare()
        waitFor { sawPath("/session/ses_1/share") }
        assertTrue(sawPath("/session/ses_1/share"))
        waitFor { vm.state.value.shareUrl == null }
        assertNull(vm.state.value.shareUrl)
    }

    @Test fun shareSurfacesUrlToCallback() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                recordedPaths.add(path)
                val body = if (path.contains("/share")) {
                    """{"id":"ses_1","projectID":"p","directory":"/w","title":"T","version":"1","time":{"created":1,"updated":2},"share":{"url":"https://share.example/s/ses_1"}}"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        var linked: String? = null
        vm.share(onLink = { linked = it })
        waitFor { linked != null }
        assertEquals("https://share.example/s/ses_1", linked)
        assertEquals("https://share.example/s/ses_1", vm.state.value.shareUrl)
    }

    @Test fun refreshReseedsNewestPage() {
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        synchronized(recordedPaths) { recordedPaths.clear() }
        vm.refresh()
        waitFor { sawPath("/session/ses_1/message") }
        assertTrue("refresh re-pulls the newest message page", sawPath("/session/ses_1/message"))
    }

    @Test fun retryReloads() {
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        synchronized(recordedPaths) { recordedPaths.clear() }
        vm.retry()
        // retry() flips loading on and re-runs start() → a fresh message fetch.
        waitFor { sawPath("/session/ses_1/message") }
        assertTrue(sawPath("/session/ses_1/message"))
    }

    @Test fun loadDiffFetchesUnifiedDiff() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                recordedPaths.add(path)
                val body = if (path.contains("/diff")) {
                    """[{"file":"a.kt","patch":"@@","additions":1,"deletions":0,"status":"modified"}]"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        val diff = kotlinx.coroutines.runBlocking { vm.loadDiff() }
        assertTrue(sawPath("/session/ses_1/diff"))
        assertEquals("a.kt", diff.firstOrNull()?.file)
    }

    @Test fun modelLabelFallsBackToIdThenResolvesDisplayName() {
        // Default prefs have an empty model id → the generic "Model" label.
        val vm = viewModel(); waitFor { !vm.state.value.loading }
        assertEquals("Model", vm.modelLabel())
        // Selecting a model (no providers loaded) → the label falls back to the id.
        vm.selectModel("openai", "gpt-5")
        assertEquals("gpt-5", vm.modelLabel())
    }
}
