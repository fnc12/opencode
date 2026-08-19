package studio.eugenezakharov.opencode.ui.session

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/** Opens a full-screen, zoomable viewer for an image attachment. Pinch or
 *  double-tap to zoom, drag to pan, ✕ to close, Save writes it to the gallery.
 *  Mirrors the iOS `ImageViewerController`. */
// Returns the shown Dialog (callers ignore it; an instrumented test uses it to
// dismiss the viewer so a leaked fullscreen window can't crash a later test).
fun showImageViewer(context: Context, bitmap: Bitmap): Dialog {
    val d = context.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()

    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        // Transparent window so a swipe-drag fades the black backdrop away and
        // reveals the transcript behind it — the "modern app" dismiss feel.
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    val imageView = ZoomableImageView(
        context,
        onDismiss = { dialog.dismiss() },
        // Fade the black backdrop as the image is dragged toward the edge.
        onDragFraction = { f -> root.background.alpha = ((1f - f) * 255).toInt().coerceIn(0, 255) },
    ).apply {
        setImageBitmap(bitmap)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    root.addView(imageView)
    dialog.setContentView(root)

    fun overlayButton(text: String, gravity: Int): Button = Button(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        background = ColorDrawable(0x66000000)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = gravity
        lp.setMargins(px(12), px(12), px(12), px(12))
        layoutParams = lp
    }

    val close = overlayButton("✕", Gravity.TOP or Gravity.START).apply {
        contentDescription = "Close"
        setOnClickListener { dialog.dismiss() }
    }
    root.addView(close)

    // Saving to the gallery via MediaStore needs no runtime permission on API 29+;
    // below that it would require WRITE_EXTERNAL_STORAGE, so hide it there.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val save = overlayButton("Save", Gravity.TOP or Gravity.END).apply {
            setOnClickListener {
                val ok = saveToGallery(context, bitmap)
                Toast.makeText(context, if (ok) "Saved to Photos" else "Couldn't save", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save)
    }

    dialog.show()
    return dialog
}

/** Writes the bitmap to the device gallery (Pictures/OpenCode). API 29+.
 *  Internal so an instrumented test can verify the MediaStore write directly. */
internal fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "opencode-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenCode")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        true
    } catch (_: Exception) {
        false
    }
}

/** An ImageView that fits its bitmap on screen at rest and supports pinch-zoom,
 *  drag-to-pan, double-tap-to-toggle-zoom, and — at rest scale — a vertical
 *  swipe (up OR down) that drags the image away and dismisses. */
// Internal (not private) so an instrumented test can mount it on a real Activity
// window and drive its gestures (pinch/pan/double-tap/dismiss) directly.
internal class ZoomableImageView(
    context: Context,
    private val onDismiss: () -> Unit,
    private val onDragFraction: (Float) -> Unit,
) : ImageView(context) {
    private val matrix_ = Matrix()
    private val baseMatrix = Matrix()
    private var minScale = 1f
    private val maxScale = 6f
    private var laidOut = false

    // Swipe-to-dismiss drag state (only when not zoomed in).
    private var dismissActive = false
    private var dismissStartY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = currentScale() * detector.scaleFactor
            val clamped = target.coerceIn(minScale, maxScale)
            val factor = clamped / currentScale()
            matrix_.postScale(factor, factor, detector.focusX, detector.focusY)
            applyMatrix()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Only pan the image when zoomed in; at rest scale a vertical drag is a
            // dismiss gesture (handled in onTouchEvent), so leave the matrix alone.
            if (currentScale() <= minScale * 1.05f) return false
            matrix_.postTranslate(-dx, -dy)
            applyMatrix()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale() > minScale * 1.05f) {
                matrix_.set(baseMatrix)
            } else {
                val factor = maxScale / 2f / currentScale()
                matrix_.postScale(factor, factor, e.x, e.y)
            }
            applyMatrix()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!laidOut || changed) { resetBase(); laidOut = true }
    }

    private fun resetBase() {
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return
        val scale = minOf(vw / iw, vh / ih)
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate((vw - iw * scale) / 2f, (vh - ih * scale) / 2f)
        minScale = scale
        matrix_.set(baseMatrix)
        applyMatrix()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix_.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun applyMatrix() { imageMatrix = matrix_ }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        handleDismissDrag(event)
        return true
    }

    /** At rest scale, follow a single-finger vertical drag with the image (up or
     *  down) and dismiss once it passes a threshold; otherwise spring back. */
    private fun handleDismissDrag(event: MotionEvent) {
        if (currentScale() > minScale * 1.05f) return // zoomed in — pan, don't dismiss
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dismissStartY = event.rawY; dismissActive = true }
            MotionEvent.ACTION_MOVE -> {
                if (!dismissActive || event.pointerCount > 1) return
                val dy = event.rawY - dismissStartY
                translationY = dy
                val f = (abs(dy) / (height * 0.5f)).coerceIn(0f, 1f)
                onDragFraction(f)
                val s = 1f - f * 0.1f
                scaleX = s; scaleY = s
            }
            MotionEvent.ACTION_UP -> {
                if (!dismissActive) return
                dismissActive = false
                val dy = event.rawY - dismissStartY
                if (abs(dy) > height * 0.18f) onDismiss() else springBack()
            }
            MotionEvent.ACTION_CANCEL -> { dismissActive = false; springBack() }
        }
    }

    private fun springBack() {
        animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(180).start()
        onDragFraction(0f)
    }
}
