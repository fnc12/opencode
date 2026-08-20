package studio.eugenezakharov.opencode

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime guard for the "tap the text field and the chat jumps up" bug.
 *
 * Measured on a real device: with edge-to-edge but ADJUST_PAN, focusing the
 * composer panned the whole window up (the content's top went off-screen). The
 * fix is windowSoftInputMode=adjustResize so the IME is delivered as insets.
 *
 * Unlike the manifest string check (a unit test), this launches the real
 * Activity and asserts the RESOLVED window attribute — so a theme or code path
 * that flips the mode back to pan is caught too. It needs no server and no
 * keyboard, so it is deterministic (not a flaky IME test).
 */
@RunWith(AndroidJUnit4::class)
class ActivitySoftInputModeTest {
    @Test
    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_* still resolve the real window attribute
    fun windowAdjustsResizeForKeyboardNotPan() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val mode = activity.window.attributes.softInputMode and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                assertEquals(
                    "the window must ADJUST_RESIZE for the IME (edge-to-edge) — " +
                        "ADJUST_PAN pans the whole chat off the top when the composer focuses",
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    mode,
                )
            }
        }
    }
}
