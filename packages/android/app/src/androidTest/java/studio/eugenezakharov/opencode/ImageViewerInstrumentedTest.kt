package studio.eugenezakharov.opencode

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.ui.session.saveToGallery
import studio.eugenezakharov.opencode.ui.session.showImageViewer

/**
 * Instrumented coverage for the image viewer's non-gesture parts: the
 * showImageViewer Dialog builder (overlay Close/Save buttons) and the
 * saveToGallery MediaStore write.
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bitmap() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    @Test fun saveToGalleryWritesToMediaStore() {
        val ctx = compose.activity
        assertTrue("bitmap must save to the gallery on API 29+", saveToGallery(ctx, bitmap()))
    }

    @Test fun showImageViewerBuildsDialogAndButtonsWork() {
        // Shows the full-screen viewer Dialog on the Activity's real window; this
        // runs the builder + both overlayButton() calls + the API-Q Save branch.
        compose.activityRule.scenario.onActivity { activity ->
            showImageViewer(activity, bitmap())
        }
        compose.waitForIdle()
        // The Save overlay button's onClick → saveToGallery + Toast.
        onView(withText("Save")).inRoot(isDialog()).perform(click())
        // The Close overlay button dismisses the dialog.
        onView(withContentDescription("Close")).inRoot(isDialog()).perform(click())
    }
}
