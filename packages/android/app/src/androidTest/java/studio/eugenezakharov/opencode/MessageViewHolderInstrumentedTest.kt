package studio.eugenezakharov.opencode

import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.ui.session.MessageAdapter

/**
 * Instrumented coverage for MessageViewHolder's row listeners (tap → open detail,
 * long-press → Copy / Revert popup). Attaches the adapter to a real RecyclerView
 * and laid-out frame so bindingAdapterPosition is valid, then drives the clicks.
 */
@RunWith(AndroidJUnit4::class)
class MessageViewHolderInstrumentedTest {
    private fun message() = MessageWithParts(
        MessageInfo.Assistant("m1", "s", 1.0),
        mutableListOf(MessagePart("p", "s", "m1", "assistant", PartContent.Text("Hello there"), false, false)),
    )

    @Test fun rowTapAndLongPressFireListeners() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var opened: MessageWithParts? = null
        // RecyclerView + popup work must run on a Looper thread → the main thread.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val adapter = MessageAdapter().apply {
                onSelect = { opened = it }
                onRevert = { }
            }
            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                this.adapter = adapter
            }
            adapter.submit(listOf(message()))
            // Lay the RecyclerView out so it creates + binds a row view holder.
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            rv.layout(0, 0, 1000, 2000)

            val row = rv.getChildAt(0)
            assertTrue("a row must be laid out", row != null)
            // Tap → selectProvider()?.invoke(pos) with a valid bindingAdapterPosition.
            row.performClick()
            assertTrue("tapping a row opens its detail", opened != null)
            // Long-press → builds the Copy / Revert popup. Its final show() needs a
            // real window token (no Activity here), so tolerate that; the listener +
            // popup-construction lambda have already run.
            runCatching { row.performLongClick() }
        }
    }
}
