package studio.eugenezakharov.opencode

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.ui.session.MessageAdapter

/**
 * Instrumented coverage for MessageViewHolder's row listeners. Mounts the adapter
 * in a RecyclerView on the test Activity's REAL window (so the long-press
 * PopupMenu can show), taps a row (onSelect), and drives the long-press popup's
 * "Copy" item (clipboard + Toast). Clicking a popup item auto-dismisses it, so
 * nothing leaks into a later test class.
 */
@RunWith(AndroidJUnit4::class)
class MessageViewHolderInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun message() = MessageWithParts(
        MessageInfo.Assistant("m1", "s", 1.0),
        mutableListOf(MessagePart("p", "s", "m1", "assistant", PartContent.Text("Hello there"), false, false)),
    )

    @Test fun rowTapOpensAndLongPressCopyWorks() {
        var opened: MessageWithParts? = null
        lateinit var row: View
        compose.activityRule.scenario.onActivity { activity ->
            val adapter = MessageAdapter().apply {
                onSelect = { opened = it }
                onRevert = {}
            }
            val rv = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                this.adapter = adapter
            }
            adapter.submit(listOf(message()))
            activity.setContentView(rv)
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            rv.layout(0, 0, 1000, 2000)
            row = rv.getChildAt(0)
            row.performClick()          // onSelect
            row.performLongClick()      // shows the Copy / Revert popup on the real window
        }
        compose.waitForIdle()
        assertTrue("tapping a row opens its detail", opened?.id == "m1")
        // Copy item → clipboard write + Toast; clicking it dismisses the popup.
        onView(withText("Copy")).inRoot(isPlatformPopup()).perform(click())
    }
}
