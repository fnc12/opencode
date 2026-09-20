package studio.eugenezakharov.opencode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.ui.session.MessageDetailScreen
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

/**
 * Instrumented coverage for the Compose MessageDetailScreen: renders a rich
 * message (thinking + text + tool + patch note), toggles the thinking card
 * expand/collapse, and taps Copy — the block-rendering branches + the copy
 * handler that Paparazzi (broken on JDK 22) can't reach.
 */
@RunWith(AndroidJUnit4::class)
class MessageDetailScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun richMessage() = MessageWithParts(
        MessageInfo.Assistant("m", "s", 1.0),
        mutableListOf(
            MessagePart("p0", "s", "m", "assistant", PartContent.Reasoning("Let me weigh the tokenizer split carefully before deciding."), false, false),
            MessagePart("p1", "s", "m", "assistant", PartContent.Text("Done — split the tokenizer."), false, false),
            MessagePart("p2", "s", "m", "assistant",
                PartContent.Tool(tool = "bash", callID = "c", status = "completed", output = "2 passed", input = mapOf("command" to "cargo test")), false, false),
            MessagePart("p3", "s", "m", "assistant", PartContent.Patch(hash = "abc", files = listOf("a.kt")), false, false),
        ),
    )

    @Test fun rendersBlocksTogglesThinkingAndCopies() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true, dynamicColor = false) {
                MessageDetailScreen(message = richMessage(), onDismiss = {})
            }
        }
        // The screen (Dialog) renders: title + the thinking teaser + the answer.
        assertTrue("assistant title", compose.onAllNodesWithText("Assistant").fetchSemanticsNodes().isNotEmpty())
        assertTrue("thinking card", compose.onAllNodesWithText("💭 Thinking").fetchSemanticsNodes().isNotEmpty())
        // (Body/Tool blocks render via native MarkdownText AndroidViews, so their
        // text isn't in the Compose semantics tree — they still execute the render
        // branches; we assert on the Compose-visible parts.)

        // Toggle the thinking card open (covers the expanded MarkdownText branch).
        compose.onNodeWithTag("detail.thinking").performClick()
        compose.waitForIdle()
        // Copy flattens every block to the clipboard (+ Toast).
        compose.onNodeWithText("Copy").performClick()
        compose.waitForIdle()
        assertTrue("copy stays composed", compose.onAllNodesWithText("Copy").fetchSemanticsNodes().isNotEmpty())
    }
}
