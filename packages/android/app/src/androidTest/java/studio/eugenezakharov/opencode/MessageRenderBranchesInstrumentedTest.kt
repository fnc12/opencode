package studio.eugenezakharov.opencode

import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.models.MessageError
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.ui.session.MessageAdapter
import studio.eugenezakharov.opencode.ui.session.MsgBlock

/**
 * Instrumented coverage for MessageAdapter.render()'s per-part branches that
 * Paparazzi would normally cover — but Paparazzi's ByteBuddy agent can't run on
 * this machine's JDK, so these branches (tool marks running/error/pending, the
 * error snippet, patch, compaction, step-start, assistant error) are exercised
 * on-device instead, where android.text.Spannable is real. Coverage merges via
 * JaCoCo.
 */
@RunWith(AndroidJUnit4::class)
class MessageRenderBranchesInstrumentedTest {
    private fun assistant(error: MessageError? = null, vararg parts: MessagePart) =
        MessageWithParts(MessageInfo.Assistant("m", "s", 1.0, error = error), parts.toMutableList())

    private fun tool(status: String, error: String? = null) = MessagePart(
        "p", "s", "m", "assistant",
        PartContent.Tool(tool = "bash", callID = "c", status = status,
            input = mapOf("command" to "cargo build"), error = error),
        false, false,
    )

    /** Concatenate the text of every rendered Text block. */
    private fun plainText(msg: MessageWithParts): String {
        val rendered = MessageAdapter.render(msg)
        return rendered.blocks.filterIsInstance<MsgBlock.Text>().joinToString("\n") { it.span.toString() }
    }

    /** Bind through a real view holder too, so the bind path runs for each branch. */
    private fun bind(msg: MessageWithParts) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val adapter = MessageAdapter()
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0) as MessageAdapter.MessageViewHolder
        holder.bind(MessageAdapter.render(msg))
    }

    @Test fun textTableTextBindsExtraBodyView() {
        // A GFM table between two paragraphs → blocks [Text, Table, Text]. The first
        // Text fills the bubble; the Table (buildTableView) and the trailing Text
        // (makeBodyTextView) mount as "extra" views — covering makeBodyTextView.
        val msg = assistant(parts = arrayOf(
            MessagePart("p", "s", "m", "assistant",
                PartContent.Text("Intro line.\n\n| Name | Type |\n|---|---|\n| id | Int |\n\nClosing line."), false, false),
        ))
        val rendered = MessageAdapter.render(msg)
        assertTrue("splits into 3 blocks", rendered.blocks.size >= 3)
        bind(msg)
    }

    @Test fun runningToolMark() {
        val msg = assistant(parts = arrayOf(tool("running")))
        assertTrue("running tool uses the … mark", plainText(msg).contains("…"))
        bind(msg)
    }

    @Test fun pendingToolMark() {
        val msg = assistant(parts = arrayOf(tool("pending")))
        assertTrue("pending tool uses the ◷ mark", plainText(msg).contains("◷"))
        bind(msg)
    }

    @Test fun errorToolShowsMarkAndSnippet() {
        val msg = assistant(parts = arrayOf(tool("error", error = "compilation failed: unexpected token")))
        val text = plainText(msg)
        assertTrue("error tool uses the ✕ mark", text.contains("✕"))
        assertTrue("error snippet is surfaced inline", text.contains("compilation failed"))
        bind(msg)
    }

    @Test fun errorToolTruncatesLongSnippet() {
        val long = "x".repeat(400)
        val msg = assistant(parts = arrayOf(tool("error", error = long)))
        val text = plainText(msg)
        assertTrue("long error snippet is truncated with an ellipsis", text.contains("…"))
        bind(msg)
    }

    @Test fun patchLineRendered() {
        val msg = assistant(parts = arrayOf(
            MessagePart("p", "s", "m", "assistant", PartContent.Patch(hash = "abc123", files = listOf("a.kt", "b.kt")), false, false),
        ))
        assertTrue("patch renders the ⌥ Patch marker", plainText(msg).contains("Patch"))
        bind(msg)
    }

    @Test fun stepStartTitleRendered() {
        val msg = assistant(parts = arrayOf(
            MessagePart("p", "s", "m", "assistant", PartContent.StepStart("Planning the change"), false, false),
        ))
        assertTrue("step-start title is rendered", plainText(msg).contains("Planning the change"))
        bind(msg)
    }

    @Test fun compactionMarkersBothVariants() {
        val auto = assistant(parts = arrayOf(
            MessagePart("p", "s", "m", "assistant", PartContent.Compaction(auto = true), false, false),
        ))
        assertTrue("auto compaction marker", plainText(auto).contains("earlier context summarized"))
        bind(auto)
        val manual = assistant(parts = arrayOf(
            MessagePart("p", "s", "m", "assistant", PartContent.Compaction(auto = false), false, false),
        ))
        assertTrue("manual compaction marker", plainText(manual).contains("context summarized"))
        bind(manual)
    }

    @Test fun assistantErrorAppended() {
        val msg = assistant(
            error = MessageError(name = "ProviderError", message = "rate limited"),
            parts = arrayOf(MessagePart("p", "s", "m", "assistant", PartContent.Text("Partial answer."), false, false)),
        )
        val text = plainText(msg)
        assertTrue("partial answer kept", text.contains("Partial answer."))
        assertTrue("error appended", text.contains("Error: rate limited"))
        bind(msg)
    }
}
