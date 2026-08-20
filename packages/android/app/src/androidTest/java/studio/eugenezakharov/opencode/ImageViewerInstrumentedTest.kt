package studio.eugenezakharov.opencode

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.ui.session.saveToGallery
import studio.eugenezakharov.opencode.ui.session.showImageViewer

/**
 * Instrumented coverage for the image viewer's non-gesture parts: the
 * showImageViewer Dialog builder (overlay Close/Save buttons + the API-Q Save
 * branch) and the saveToGallery MediaStore write. The overlay buttons' onClick
 * handlers aren't driven here — Espresso interaction with the fullscreen
 * transparent Dialog hangs the shared instrumentation; ActivityScenario tears the
 * dialog down at end of test.
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bitmap() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    @Test fun saveToGalleryWritesToMediaStore() {
        assertTrue("bitmap must save to the gallery on API 29+", saveToGallery(compose.activity, bitmap()))
    }

    @Test fun showImageViewerBuildsDialog() {
        // Shows the full-screen viewer Dialog on the Activity's real window — runs
        // the builder + both overlayButton() calls + the API-Q Save branch.
        lateinit var dialog: android.app.Dialog
        compose.activityRule.scenario.onActivity { activity ->
            dialog = showImageViewer(activity, bitmap())
        }
        compose.waitForIdle()
        assertTrue("dialog is showing", dialog.isShowing)
        // Dismiss so the fullscreen window can't leak into the next test class.
        compose.activityRule.scenario.onActivity { dialog.dismiss() }
    }
}
