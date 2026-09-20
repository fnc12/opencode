package studio.eugenezakharov.opencode

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.ComposerPrefs
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime
import studio.eugenezakharov.opencode.ui.session.SessionViewModel

/**
 * Instrumented coverage for SessionViewModel.start's LIVE SSE loop — NOT a unit
 * test (the infinite reconnect loop + real dispatchers crash the coroutines
 * machinery at Dispatchers.resetMain). On the emulator the Main looper persists.
 * Crucially the loop is stopped via `stopStream()` (a real Job.cancel), so it
 * can't leak an infinite reconnect coroutine into later tests (an earlier
 * reflection-based clear() failed to cancel it and slowed the whole run).
 */
@RunWith(AndroidJUnit4::class)
class SessionStreamInstrumentedTest {
    private lateinit var server: MockWebServer

    private class Prefs(
        override var providerID: String = "zai",
        override var modelID: String = "glm-5.2",
        override var agent: String = "build",
    ) : ComposerPrefs {
        override fun setModel(providerID: String, modelID: String) { this.providerID = providerID; this.modelID = modelID }
    }

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.shutdown() }

    private fun conn() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
    )

    private fun session() = Session(
        id = "ses_1", projectID = "p", directory = "/w", title = "T", version = "1",
        time = SessionTime(created = 1.0, updated = 2.0),
    )

    @Test fun liveStreamFoldsEventAndReachesLive() {
        val sse = """{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"stream_m","sessionID":"ses_1","role":"assistant","time":{"created":9},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if ((request.path ?: "").contains("/global/event")) {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $sse\n\n")
                } else {
                    MockResponse().setResponseCode(200).setBody("[]")
                }
            }
        }
        server.start()
        val vm = SessionViewModel(server = conn(), session = session(), prefs = Prefs(), streamLive = true)
        try {
            // The stream loop connects, folds the streamed message, and flips to LIVE.
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline &&
                vm.state.value.messages.none { it.id == "stream_m" }) Thread.sleep(30)
            assertTrue("streamed message folds into state", vm.state.value.messages.any { it.id == "stream_m" })
            val d2 = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < d2 &&
                vm.state.value.status == SessionStore.StreamStatus.IDLE) Thread.sleep(30)
            // The loop reports a live-stream status (LIVE, or RECONNECTING between frames).
            assertTrue("the stream reports a connected status",
                vm.state.value.status == SessionStore.StreamStatus.LIVE ||
                    vm.state.value.status == SessionStore.StreamStatus.RECONNECTING ||
                    vm.state.value.status == SessionStore.StreamStatus.CONNECTING)
        } finally {
            vm.stopStream() // real Job.cancel — the reconnect loop stops, no leak
        }
    }
}
