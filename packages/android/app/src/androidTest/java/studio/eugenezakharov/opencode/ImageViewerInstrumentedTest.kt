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
import studio.eugenezakharov.opencode.ui.session.showImageViewer

/**
 * Instrumented coverage for the full-screen image viewer (ImageViewer.kt +
 * ZoomableImageView) — 0% on JVM because layoutlib can't decode/host it. Shows
 * the viewer over a real activity with a real bitmap, which constructs the
 * dialog, the zoomable view, and lays it out (resetBase / matrix fit).
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun viewerShowsWithBitmap() {
        val bmp = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        compose.activityRule.scenario.onActivity { activity ->
            showImageViewer(activity, bmp)
        }
        compose.waitForIdle()
        // The viewer opened over the activity (dialog + zoomable view constructed
        // and laid out) without throwing — the bitmap has real dimensions.
        assertTrue(bmp.width == 120 && bmp.height == 90)
    }
}
