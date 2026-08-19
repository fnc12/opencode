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

/** In-memory [ComposerPrefs] so the ViewModel needs no Android Context. */
private class FakeComposerPrefs(
    override var providerID: String = "",
    override var modelID: String = "",
    override var agent: String = "build",
) : ComposerPrefs {
    override fun setModel(providerID: String, modelID: String) {
        this.providerID = providerID; this.modelID = modelID
    }
}

/**
 * [SessionViewModel] logic driven against a fake server (MockWebServer), with the
 * live SSE loop disabled (`streamLive = false`) so the initial load completes.
 * Uses an unconfined main dispatcher + real polling (`waitFor`) because
 * ServerConnection does its IO on `Dispatchers.IO`, which escapes virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("[]")
        }
        server.start()
    }

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
}
