package studio.eugenezakharov.opencode

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Reproduces the "question dialog you can't escape" bug with the REAL captured
 * payload (see [QuestionFixtures.wifiDensepose]): a 3-question / 11-option
 * request rendered the dock taller than the screen with no scrolling, pushing
 * Skip/Submit off-screen. The dock must keep its action row reachable no matter
 * how big the questionnaire is. Mirrors iOS `QuestionDockLayoutUITests`.
 */
@RunWith(AndroidJUnit4::class)
class QuestionDockLayoutUITest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    // A fresh emulator shows the POST_NOTIFICATIONS system dialog over the
    // Connect screen, blocking every interaction — pre-grant it.
    @get:Rule
    val grantNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val hostBase = "http://10.0.2.2:4096"
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
    fun hugeRealQuestionKeepsActionsReachable() {
        assumeTrue("live server not reachable at $hostBase; start the tunnel to run this UI test", serverReachable())

        val stamp = System.currentTimeMillis()
        val title = "QLAYOUT $stamp"
        createSession(title)

        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("UITEST_RESET", true)
            putExtra("UITEST_QUESTION_JSON", QuestionFixtures.wifiDensepose)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    composeRule.onAllNodes(hasText("Direct")).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag("connect.direct").performClick()
            composeRule.onNodeWithTag("connect.serverURL").performTextInput(hostBase)
            password?.let { composeRule.onNodeWithTag("connect.password").performTextInput(it) }
            composeRule.onNodeWithTag("connect.button").performClick()

            clickNativeText(projectName, timeoutMs = 20_000)

            clickNativeText(title, timeoutMs = 20_000)

            // A fresh session has no file changes — the diff button must be hidden.
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodes(hasTestTag("session.shell")).fetchSemanticsNodes().isNotEmpty()
            }
            check(composeRule.onAllNodes(hasTestTag("session.diff")).fetchSemanticsNodes().isEmpty()) {
                "diff button must hide when there is no diff"
            }

            // The real payload's dock must appear…
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(hasText("Модель ESP32")).fetchSemanticsNodes().isNotEmpty()
            }

            // …with the escape hatches ON SCREEN despite the huge content. Pre-fix
            // the dock had no height bound or scrolling, so both buttons sat far
            // below the screen edge.
            composeRule.onNodeWithTag("question.reject").assertIsDisplayed()
            composeRule.onNodeWithTag("question.submit").assertIsDisplayed()

            // The deep options must exist inside the dock's internal scroll: the
            // last question's last option.
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodes(hasTestTag("Координаты (x,y) в комнате")).fetchSemanticsNodes().isNotEmpty()
            }

            // Auto-advance: answering a single-select question scrolls the next
            // unanswered one into view; the whole questionnaire ends with an
            // enabled Submit.
            composeRule.onNodeWithTag("ESP32-S3 (8MB flash)").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Где работает агрегатор").assertIsDisplayed(); true
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag("Домашний laptop/RPi в той же WiFi-сети").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithText("Цель по точности").assertIsDisplayed(); true
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag("question.submit").assertIsNotEnabled()
            composeRule.onNodeWithTag("Presence по комнатам").performClick() // Q3 is multi-select
            composeRule.onNodeWithTag("question.submit").assertIsEnabled()
        }
    }

    /** Reading mode: with a pending question, scrolling up into history must
     *  collapse the dock to a one-line pill; tapping the pill returns to the
     *  bottom where the full dock lives. (Covers the full roundtrip — the iOS
     *  UI test covers only the collapse half; see its comment.) */
    @Test
    fun dockCollapsesToPillWhileReadingHistoryAndReturns() {
        assumeTrue("live server not reachable at $hostBase; start the tunnel to run this UI test", serverReachable())

        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("UITEST_RESET", true)
            putExtra("UITEST_QUESTION_JSON", QuestionFixtures.wifiDensepose)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    composeRule.onAllNodes(hasText("Direct")).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag("connect.direct").performClick()
            composeRule.onNodeWithTag("connect.serverURL").performTextInput(hostBase)
            password?.let { composeRule.onNodeWithTag("connect.password").performTextInput(it) }
            composeRule.onNodeWithTag("connect.button").performClick()

            // A session with real history to read (the fixture question rides along).
            clickNativeText("sqlite2orm", timeoutMs = 20_000)
            clickNativeText("Описание проекта", timeoutMs = 20_000)

            // Full dock at the bottom.
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(hasTestTag("question.reject")).fetchSemanticsNodes().isNotEmpty()
            }

            // Scroll up into history → the dock must collapse to the pill.
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(androidx.recyclerview.widget.RecyclerView::class.java),
            ).perform(
                androidx.test.espresso.action.ViewActions.swipeDown(),
                androidx.test.espresso.action.ViewActions.swipeDown(),
            )
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodes(hasTestTag("question.pill")).fetchSemanticsNodes().isNotEmpty()
            }
            check(composeRule.onAllNodes(hasTestTag("question.reject")).fetchSemanticsNodes().isEmpty()) {
                "full dock must be hidden while reading"
            }

            // REGRESSION (reported live on iOS): the collapse must not feed back
            // into its own trigger — the pill must be STABLE seconds later.
            Thread.sleep(2_500)
            check(composeRule.onAllNodes(hasTestTag("question.pill")).fetchSemanticsNodes().isNotEmpty()) {
                "pill must remain while reading — no yank-back loop"
            }
            check(composeRule.onAllNodes(hasTestTag("question.reject")).fetchSemanticsNodes().isEmpty()) {
                "full dock must stay hidden while reading"
            }

            // Tap the pill → back at the bottom with the full dock.
            composeRule.onNodeWithTag("question.pill").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodes(hasTestTag("question.reject")).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun serverReachable(): Boolean = runCatching {
        http.newCall(
            Request.Builder().url("$hostBase/global/health").apply { auth?.let { header("Authorization", it) } }.build(),
        ).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun createSession(title: String) {
        val enc = URLEncoder.encode(dir, "UTF-8")
        val body = """{"title":"$title"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$hostBase/session?directory=$enc")
            .apply { auth?.let { header("Authorization", it) } }.post(body).build()
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
