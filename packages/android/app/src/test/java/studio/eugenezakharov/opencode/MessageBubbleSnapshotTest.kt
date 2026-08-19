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
}
