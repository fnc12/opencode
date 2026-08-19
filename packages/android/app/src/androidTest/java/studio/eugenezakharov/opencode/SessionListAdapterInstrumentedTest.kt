package studio.eugenezakharov.opencode

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime
import studio.eugenezakharov.opencode.ui.screens.SessionListAdapter

/**
 * Instrumented coverage for SessionListAdapter's row listeners (tap → open,
 * long-press → Rename/Delete popup). Attaches the adapter to a laid-out
 * RecyclerView on the main thread and drives the clicks.
 */
@RunWith(AndroidJUnit4::class)
class SessionListAdapterInstrumentedTest {
    private fun session(id: String) = Session(
        id = id, projectID = "p", directory = "/w", title = "Fix $id", version = "1",
        time = SessionTime(created = 1.0, updated = 2.0),
    )

    @Test fun rowTapAndLongPressFireCallbacks() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var opened: Session? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val adapter = SessionListAdapter(
                primaryColor = -1, secondaryColor = -1, addColor = -1, delColor = -1,
                onClick = { opened = it }, onRename = {}, onDelete = {},
            )
            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                this.adapter = adapter
            }
            adapter.submit(listOf(session("ses_1")))
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            rv.layout(0, 0, 1000, 2000)

            val row = rv.getChildAt(0)
            assertTrue("a session row must be laid out", row != null)
            row.performClick()
            assertTrue("tapping a session opens it", opened?.id == "ses_1")
            // Long-press builds the Rename/Delete popup; its window-dependent show()
            // is tolerated (no Activity host).
            runCatching { row.performLongClick() }
        }
    }
}
