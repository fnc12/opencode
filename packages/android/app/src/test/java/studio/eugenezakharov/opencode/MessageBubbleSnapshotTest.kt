package studio.eugenezakharov.opencode

import android.view.View
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.ui.session.MessageAdapter

/**
 * Paparazzi goldens for the RecyclerView message bubble rendered by the REAL
 * [MessageAdapter] (View-based, was ~0%). Builds a real MessageViewHolder, binds
 * a rendered message, and snapshots its itemView — so the adapter's onCreate +
 * render + bind path (role/meta styling, bubble background, markdown body) all
 * execute and the layout is pinned. Mirrors the iOS MessageCell snapshots.
 */
class MessageBubbleSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = com.android.resources.NightMode.NIGHT),
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private fun bubble(message: MessageWithParts): View {
        val adapter = MessageAdapter()
        // viewType 0 = TYPE_MESSAGE (not the footer); dark night-mode config above.
        val holder = adapter.onCreateViewHolder(FrameLayout(paparazzi.context), 0)
            as MessageAdapter.MessageViewHolder
        holder.bind(MessageAdapter.render(message))
        return holder.itemView
    }

    private fun textPart(text: String) =
        MessagePart("p", "s", "m", "assistant", PartContent.Text(text), false, false)

    @Test fun assistantTextBubble() {
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(textPart("Here is the reply — **bold** and `code`, split into a clear sentence.")),
        )
        paparazzi.snapshot(bubble(msg))
    }

    @Test fun userBubble() {
        val msg = MessageWithParts(
            MessageInfo.User("m", "s", 1.0),
            mutableListOf(textPart("Fix the parser and add a test, please.")),
        )
        paparazzi.snapshot(bubble(msg))
    }

    @Test fun tableBubble() {
        // A GFM table is lifted into a real grid (buildTableView in MessageAdapterKt).
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(textPart("| Name | Type |\n|---|---|\n| id | Int |\n| title | String |")),
        )
        paparazzi.snapshot(bubble(msg))
    }

    @Test fun textThenTableBubble() {
        // Leading text (body TextView) + a trailing table (extra → makeBodyTextView
        // path for the text run before the grid).
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(textPart("Here are the columns:\n\n| Name | Type |\n|---|---|\n| id | Int |")),
        )
        paparazzi.snapshot(bubble(msg))
    }

    @Test fun reasoningBubble() {
        // A reasoning part collapses to a "💭 Thinking" marker before the answer.
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(
                MessagePart("p1", "s", "m", "assistant", PartContent.Reasoning("Let me think about the tokenizer…"), false, false),
                textPart("Done — split the tokenizer and added a test."),
            ),
        )
        paparazzi.snapshot(bubble(msg))
    }

    @Test fun fileRefChipFallback() {
        // A file part whose bitmap can't be decoded (layoutlib can't decode PNGs,
        // so this exercises the non-image fallback) renders the "📎 filename"
        // chip. The inline-image path (makeImageView) needs an instrumented test.
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAF0lEQVR4nGP8z8Dwn4GBgYGJAQ0AAA5+AgHqtAxvAAAAAElFTkSuQmCC"
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(
                MessagePart("p", "s", "m", "assistant",
                    PartContent.FileRef(filename = "shot.png", url = png, mime = "image/png"), false, false),
            ),
        )
        paparazzi.snapshot(bubble(msg))
    }

    @Test fun toolRowBubble() {
        // A completed tool renders as a compact one-line row (✓ bash …).
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(
                MessagePart(
                    "p", "s", "m", "assistant",
                    PartContent.Tool(
                        tool = "bash", callID = "c", status = "completed",
                        input = mapOf("command" to "cargo test"),
                    ),
                    false, false,
                ),
            ),
        )
        paparazzi.snapshot(bubble(msg))
    }
}
