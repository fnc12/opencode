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
import androidx.test.rule.GrantPermissionRule
import okhttp3.Credentials
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

    // A fresh emulator shows the POST_NOTIFICATIONS system dialog over the
    // Connect screen, blocking every interaction — pre-grant it.
    @get:Rule
    val grantNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val hostBase = "http://10.0.2.2:4096"
    private val appBase = "http://10.0.2.2:4096"
    private val dir = "/mnt/data/sources/sqlite_orm"
    private val projectName = "sqlite_orm"
    // Server password comes from the instrumentation args (public repo — no
    // hardcoded credentials): -Pandroid.testInstrumentationRunnerArguments.opencodePassword=…
    private val password: String? =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("opencodePassword")
    private val auth: String? = password?.let { Credentials.basic("opencode", it) }
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
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    composeRule.onAllNodes(hasText("Direct")).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag("connect.direct").performClick()
            composeRule.onNodeWithTag("connect.serverURL").performTextInput(appBase)
            password?.let { composeRule.onNodeWithTag("connect.password").performTextInput(it) }
            composeRule.onNodeWithTag("connect.button").performClick()

            // Projects → the sqlite_orm project.
            clickNativeText(projectName, timeoutMs = 20_000)

            // Sessions → the session we just created.
            clickNativeText(title, timeoutMs = 20_000)

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
        http.newCall(Request.Builder().url("$hostBase/global/health").apply { auth?.let { header("Authorization", it) } }.build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun createSession(title: String) {
        val enc = URLEncoder.encode(dir, "UTF-8")
        val body = """{"title":"$title"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$hostBase/session?directory=$enc").apply { auth?.let { header("Authorization", it) } }.post(body).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "create session HTTP ${it.code}" } }
    }

    /** Clicks a NATIVE view (RecyclerView rows — the project/session lists are
     *  not Compose, so compose semantics can't see them), retrying while data
     *  loads. */
    private fun clickNativeText(text: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(text))
                    .perform(androidx.test.espresso.action.ViewActions.click())
                return
            } catch (e: Throwable) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(500)
            }
        }
    }
}
