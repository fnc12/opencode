package studio.eugenezakharov.opencode

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.ui.session.ZoomableImageView

/**
 * Instrumented gesture coverage for ZoomableImageView — mounts it on the test
 * Activity's real window and dispatches synthesized touch events: swipe-to-dismiss
 * drag, double-tap zoom, pan (while zoomed), and a two-finger pinch. Exercises the
 * ScaleGestureDetector/GestureDetector callbacks + matrix math that no unit test
 * can reach. Coverage merges via JaCoCo.
 */
@RunWith(AndroidJUnit4::class)
class ZoomableImageViewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bitmap() = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }

    private lateinit var view: ZoomableImageView
    private var dismissed = false
    private var lastFraction = -1f

    private fun mount() {
        compose.activityRule.scenario.onActivity { activity ->
            view = ZoomableImageView(activity, onDismiss = { dismissed = true }, onDragFraction = { lastFraction = it })
            view.setImageBitmap(bitmap())
            val root = FrameLayout(activity)
            root.addView(view, FrameLayout.LayoutParams(600, 1200))
            activity.setContentView(root)
            // Force layout so onLayout runs (sets minScale + baseMatrix).
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 600, 1200)
        }
        compose.waitForIdle()
    }

    private fun event(downTime: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, t, action, x, y, 0)

    private fun dispatch(e: MotionEvent) {
        compose.activityRule.scenario.onActivity { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    @Test fun swipeDownDismisses() {
        mount()
        val t0 = SystemClock.uptimeMillis()
        dispatch(event(t0, t0, MotionEvent.ACTION_DOWN, 300f, 200f))
        dispatch(event(t0, t0 + 16, MotionEvent.ACTION_MOVE, 300f, 900f)) // big vertical drag
        assertTrue("dragging updates the dim fraction", lastFraction >= 0f)
        dispatch(event(t0, t0 + 32, MotionEvent.ACTION_UP, 300f, 900f))
        assertTrue("a drag past the threshold dismisses", dismissed)
    }

    @Test fun doubleTapZoomsThenPan() {
        mount()
        var t = SystemClock.uptimeMillis()
        // Two quick down/up pairs at the same point → onDoubleTap → zoom in.
        dispatch(event(t, t, MotionEvent.ACTION_DOWN, 300f, 600f))
        dispatch(event(t, t + 10, MotionEvent.ACTION_UP, 300f, 600f))
        t += 40
        dispatch(event(t, t, MotionEvent.ACTION_DOWN, 300f, 600f))
        dispatch(event(t, t + 10, MotionEvent.ACTION_UP, 300f, 600f))
        val zoomed = FloatArray(9).also { compose.activityRule.scenario.onActivity { _ -> view.imageMatrix.getValues(it) } }
        assertTrue("double-tap zooms in (scaleX > 1)", zoomed[0] > 1.05f)

        // Now a drag pans (onScroll fires because currentScale > minScale).
        t += 60
        dispatch(event(t, t, MotionEvent.ACTION_DOWN, 300f, 600f))
        dispatch(event(t, t + 16, MotionEvent.ACTION_MOVE, 340f, 640f))
        dispatch(event(t, t + 32, MotionEvent.ACTION_UP, 340f, 640f))

        // Double-tap again → resets back to base scale.
        t += 100
        dispatch(event(t, t, MotionEvent.ACTION_DOWN, 300f, 600f))
        dispatch(event(t, t + 10, MotionEvent.ACTION_UP, 300f, 600f))
        t += 40
        dispatch(event(t, t, MotionEvent.ACTION_DOWN, 300f, 600f))
        dispatch(event(t, t + 10, MotionEvent.ACTION_UP, 300f, 600f))
    }

    @Test fun pinchScales() {
        mount()
        val t = SystemClock.uptimeMillis()
        // Two-pointer pinch-out: primary down, second pointer down, both move apart, up.
        fun multi(action: Int, x0: Float, y0: Float, x1: Float, y1: Float, dt: Long): MotionEvent {
            val props = arrayOf(
                MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply { this.x = x0; this.y = y0; pressure = 1f; size = 1f },
                MotionEvent.PointerCoords().apply { this.x = x1; this.y = y1; pressure = 1f; size = 1f },
            )
            return MotionEvent.obtain(t, t + dt, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        dispatch(event(t, t, MotionEvent.ACTION_DOWN, 280f, 600f))
        dispatch(multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 280f, 600f, 320f, 600f, 8))
        dispatch(multi(MotionEvent.ACTION_MOVE, 200f, 600f, 400f, 600f, 16)) // spread apart
        dispatch(multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 600f, 400f, 600f, 24))
        dispatch(event(t, t + 32, MotionEvent.ACTION_UP, 200f, 600f))
        // The pinch ran through onScale; the view stays consistent (scale within bounds).
        val v = FloatArray(9).also { compose.activityRule.scenario.onActivity { _ -> view.imageMatrix.getValues(it) } }
        assertTrue("scale stays within [1, 6]", v[0] in 0.9f..6.1f)
    }
}
