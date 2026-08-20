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
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime
import studio.eugenezakharov.opencode.ui.screens.SessionListAdapter

/**
 * Instrumented coverage for SessionListAdapter's row listeners on the test
 * Activity's REAL window: tap → onClick; long-press → the Rename/Delete popup,
 * whose items fire onRename / onDelete. Clicking a popup item auto-dismisses it.
 */
@RunWith(AndroidJUnit4::class)
class SessionListAdapterInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun session(id: String) = Session(
        id = id, projectID = "p", directory = "/w", title = "Fix $id", version = "1",
        time = SessionTime(created = 1.0, updated = 2.0),
    )

    @Test fun tapAndPopupRenameDeleteFireCallbacks() {
        var opened: Session? = null
        var renamed: Session? = null
        var deleted: Session? = null
        lateinit var row: View
        compose.activityRule.scenario.onActivity { activity ->
            val adapter = SessionListAdapter(
                primaryColor = -1, secondaryColor = -1, addColor = -1, delColor = -1,
                onClick = { opened = it }, onRename = { renamed = it }, onDelete = { deleted = it },
            )
            val rv = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                this.adapter = adapter
            }
            adapter.submit(listOf(session("ses_1")))
            activity.setContentView(rv)
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            rv.layout(0, 0, 1000, 2000)
            row = rv.getChildAt(0)
            row.performClick()      // onClick
            row.performLongClick()  // shows the Rename / Delete popup
        }
        compose.waitForIdle()
        assertTrue("tap opens the session", opened?.id == "ses_1")
        // Delete item → onDelete (auto-dismisses the popup).
        onView(withText("Delete")).inRoot(isPlatformPopup()).perform(click())
        assertTrue("Delete fires onDelete", deleted?.id == "ses_1")

        // Re-open the popup and pick Rename → onRename.
        compose.activityRule.scenario.onActivity { row.performLongClick() }
        compose.waitForIdle()
        onView(withText("Rename")).inRoot(isPlatformPopup()).perform(click())
        assertTrue("Rename fires onRename", renamed?.id == "ses_1")
    }
}
