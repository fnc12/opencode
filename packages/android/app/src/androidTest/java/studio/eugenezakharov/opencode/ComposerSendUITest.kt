package studio.eugenezakharov.opencode

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.MediaType.Companion.toMediaType
import org.hamcrest.Matchers.containsString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Drives the real UI on the emulator end to end, mirroring iOS
 * `ComposerSendUITests`: launch (reset to a clean state), connect in Direct mode
 * to the live server (http://10.0.2.2:4096 from the app), navigate to a project
 * and a freshly-created session, type a prompt in the composer, send it, and
 * assert the user message and the assistant reply render.
 *
 * Requires a reachable OpenCode server (an SSH tunnel on the Mac at
 * 127.0.0.1:4096). Both the instrumentation (test process) and the app run
 * INSIDE the emulator, so both reach the Mac host via 10.0.2.2 — unlike the iOS
 * simulator, the Android emulator does not share the host's loopback. Skipped
 * (not failed) if the server is unreachable.
 */
@RunWith(AndroidJUnit4::class)
class ComposerSendUITest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    // The Mac host as seen from inside the emulator (both test process and app).
    private val hostBase = "http://10.0.2.2:4096"
    private val appBase = "http://10.0.2.2:4096"
    private val dir = "/mnt/data/sources/sqlite_orm"
    private val projectName = "sqlite_orm"
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun sendMessageFromComposer() {
        assumeTrue("live server not reachable at $hostBase; start the tunnel to run this UI test", serverReachable())

        val stamp = System.currentTimeMillis()
        val sessionTitle = "UITEST $stamp"
        createSession(sessionTitle)

        // Launch with reset so we always start on the Connect screen.
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("UITEST_RESET", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            // Connect (Direct mode) to the tunnel host.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodes(hasText("Direct")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("connect.direct").performClick()
            composeRule.onNodeWithTag("connect.serverURL").performTextInput(appBase)
            composeRule.onNodeWithTag("connect.button").performClick()

            // Projects → the sqlite_orm project.
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodes(hasText(projectName)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(projectName).performClick()

            // Sessions → the session we just created.
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodes(hasText(sessionTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(sessionTitle).performClick()

            // Composer: type a prompt; the send button enables once a model is auto-selected.
            val message = "uitest ping $stamp"
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(hasTestTag("composer.field")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("composer.field").performTextInput(message)
            composeRule.waitUntil(timeoutMillis = 20_000) {
                runCatching { composeRule.onNodeWithTag("composer.send").assertIsEnabled(); true }
                    .getOrDefault(false)
            }
            composeRule.onNodeWithTag("composer.send").performClick()

            // The conversation list is a RecyclerView (Android Views, not Compose),
            // so assert its content with Espresso. The user message echoes back over
            // the SSE stream; the assistant reply ("build" role label) follows.
            assertTrue("sent message did not render", waitForView(20_000) { onViewWithText(containsString(message)) })
            assertTrue("assistant did not reply", waitForView(60_000) { onViewWithText("build") })
        }
    }

    /** Polls until an Espresso view assertion passes or the deadline elapses. */
    private fun waitForView(timeoutMillis: Long, check: () -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { check() }.isSuccess) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun onViewWithText(text: String) {
        onView(withText(text)).check(matches(isDisplayed()))
    }

    private fun onViewWithText(matcher: org.hamcrest.Matcher<String>) {
        onView(withText(matcher)).check(matches(isDisplayed()))
    }

    private fun serverReachable(): Boolean = runCatching {
        http.newCall(Request.Builder().url("$hostBase/global/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun createSession(title: String) {
        val enc = URLEncoder.encode(dir, "UTF-8")
        val body = """{"title":"$title"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$hostBase/session?directory=$enc").post(body).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "create session HTTP ${it.code}" } }
    }
}
