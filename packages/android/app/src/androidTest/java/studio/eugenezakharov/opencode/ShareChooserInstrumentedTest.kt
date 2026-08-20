package studio.eugenezakharov.opencode

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
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

/**
 * Covers the "Share link" flow — share() succeeds and the screen launches the
 * system share chooser. Espresso Intents STUBS the outgoing intent so no real
 * chooser Activity launches (which would leak a window into later tests), then
 * asserts an ACTION_CHOOSER was fired.
 */
@RunWith(AndroidJUnit4::class)
class ShareChooserInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: MockWebServer

    private class Prefs(
        override var providerID: String = "zai",
        override var modelID: String = "glm-5.2",
        override var agent: String = "build",
    ) : ComposerPrefs {
        override fun setModel(providerID: String, modelID: String) { this.providerID = providerID; this.modelID = modelID }
    }

    @Before fun setUp() {
        Intents.init()
        // Swallow every outgoing intent (the chooser) so nothing actually launches.
        Intents.intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = if ((request.path ?: "").contains("/share")) {
                    """{"id":"ses_1","projectID":"p","directory":"/w","title":"T","version":"1","time":{"created":1,"updated":2},"share":{"url":"https://share.example/s/ses_1"}}"""
                } else {
                    "[]"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After fun tearDown() {
        Intents.release()
        server.shutdown()
    }

    private fun advanceUntil(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            compose.mainClock.advanceTimeBy(120); Thread.sleep(40)
        }
    }

    @Test fun shareLinkLaunchesChooser() {
        val conn = ServerConnection(
            ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = server.url("/").toString().trimEnd('/')),
        )
        val session = Session(
            id = "ses_1", projectID = "p", directory = "/w", title = "T", version = "1",
            time = SessionTime(created = 1.0, updated = 2.0),
        )
        val vm = SessionViewModel(server = conn, session = session, prefs = Prefs(), streamLive = false)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && vm.state.value.loading) Thread.sleep(20)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                SessionScreen(viewModel = vm, session = session, onBack = {})
            }
        }
        // Open the share menu → tap "Share link" → share() → startActivity(chooser).
        compose.onNodeWithTag("session.share").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        advanceUntil(3000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Share link").onFirst().performClick()
        // share() posts, its callback builds + launches the chooser; Intents caught it.
        advanceUntil(5000) {
            runCatching { Intents.intended(hasAction(Intent.ACTION_CHOOSER)); true }.getOrDefault(false)
        }
        Intents.intended(hasAction(Intent.ACTION_CHOOSER))
    }
}
