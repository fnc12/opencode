package studio.eugenezakharov.opencode

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Issue #51 end to end: start work on a fresh server by opening a folder. Launch
 * (reset to a clean state), connect in Direct mode to the live server, tap the
 * "Open folder" action on the Projects screen, type a server-absolute directory,
 * confirm, and assert we land straight in a session (the composer field appears).
 *
 * Both the instrumentation and the app run inside the emulator, so both reach the
 * Mac host via 10.0.2.2 (the Android emulator does not share the host loopback).
 * Skipped (not failed) if the server is unreachable. Mirrors the iOS OpenFolder UI test.
 */
@RunWith(AndroidJUnit4::class)
class OpenFolderUITest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val hostBase = "http://10.0.2.2:4096"
    private val appBase = "http://10.0.2.2:4096"
    private val dir = "/mnt/data/sources/sqlite_orm"
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun openFolderCreatesAndOpensSession() {
        assumeTrue("live server not reachable at $hostBase; start the tunnel to run this UI test", serverReachable())

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

            // Projects screen: wait for the Open-folder action, then open the browser.
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodes(hasTestTag("projects.openFolder")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("projects.openFolder").performClick()

            // The folder browser lists the root's subfolders (the server has /mnt).
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodes(hasTestTag("openFolder.path")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(hasTestTag("dir.mnt")).fetchSemanticsNodes().isNotEmpty()
            }

            // Clear the current path, type the server-absolute target, and open it.
            composeRule.onNodeWithTag("openFolder.path").performTextClearance()
            composeRule.onNodeWithTag("openFolder.path").performTextInput(dir)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { composeRule.onNodeWithTag("openFolder.create").assertIsEnabled(); true }
                    .getOrDefault(false)
            }
            composeRule.onNodeWithTag("openFolder.create").performClick()

            // We should land directly in the freshly-created session: the composer appears.
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodes(hasTestTag("composer.field")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("composer.field").assertIsDisplayed()
        }
    }

    private fun serverReachable(): Boolean = runCatching {
        http.newCall(Request.Builder().url("$hostBase/global/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
