package studio.eugenezakharov.opencode

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.MediaType.Companion.toMediaType
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
 * Drives the question dock on the emulator (#28), mirroring iOS
 * `QuestionDockUITests` and the Android `PermissionDockUITest`: open a session
 * with an injected pending question (launch flag `UITEST_QUESTION`), assert the
 * dock + question text render, tap an option, tap **Submit**, and assert it
 * dismisses. Verifies the dock UI + reply wiring without the server being
 * configured to "ask".
 *
 * Requires a reachable server (SSH tunnel on the Mac) for the connect→session
 * navigation; both the test process and the app reach it at 10.0.2.2 inside the
 * emulator. Skipped (not failed) if unreachable.
 */
@RunWith(AndroidJUnit4::class)
class QuestionDockUITest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val hostBase = "http://10.0.2.2:4096"
    private val appBase = "http://10.0.2.2:4096"
    private val dir = "/mnt/data/sources/sqlite_orm"
    private val projectName = "sqlite_orm"
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun answerQuestionFromDock() {
        assumeTrue("live server not reachable at $hostBase; start the tunnel to run this UI test", serverReachable())

        val stamp = System.currentTimeMillis()
        val title = "QTEST $stamp"
        createSession(title)

        // Launch with reset (clean state) + injected synthetic question.
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("UITEST_RESET", true)
            putExtra("UITEST_QUESTION", true)
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
                composeRule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(title).performClick()

            // The injected question renders a dock with the question text + a Submit button.
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(hasTestTag("question.submit")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Which database?").assertIsDisplayed()

            // Submit is disabled until an option is selected; pick Option A.
            composeRule.onNodeWithTag("Option A").performClick()

            // Tapping Submit optimistically dismisses the dock.
            composeRule.onNodeWithTag("question.submit").performClick()
            composeRule.waitUntil(timeoutMillis = 8_000) {
                composeRule.onAllNodes(hasTestTag("question.submit")).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "dock didn't dismiss after Submit",
                composeRule.onAllNodes(hasTestTag("question.submit")).fetchSemanticsNodes().isEmpty(),
            )
        }
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
