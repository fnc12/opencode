package studio.eugenezakharov.opencode

import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
 * Instrumented coverage for the inline-image render path (makeImageView), which
 * Paparazzi can't reach because layoutlib can't decode PNGs. On a real device the
 * data-URL bitmap decodes and the bubble mounts an ImageView.
 */
@RunWith(AndroidJUnit4::class)
class MessageAdapterImageInstrumentedTest {
    private fun hasImageView(view: android.view.View): Boolean {
        if (view is ImageView) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) if (hasImageView(view.getChildAt(i))) return true
        }
        return false
    }

    @Test fun imageAttachmentMountsImageView() {
        // Build a guaranteed-valid PNG data URL at runtime (decodes on-device,
        // unlike layoutlib which can't decode PNGs at all).
        val bmp = android.graphics.Bitmap.createBitmap(16, 12, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.CYAN)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        val png = "data:image/png;base64,$b64"
        val msg = MessageWithParts(
            MessageInfo.Assistant("m", "s", 1.0),
            mutableListOf(
                MessagePart("p", "s", "m", "assistant",
                    PartContent.FileRef(filename = "shot.png", url = png, mime = "image/png"), false, false),
            ),
        )
        // On a real device the data-URL PNG decodes → render lifts it to an Image
        // block (unlike layoutlib, where it fell back to the chip).
        val rendered = MessageAdapter.render(msg)
        val hasImageBlock = rendered.blocks.any {
            it is studio.eugenezakharov.opencode.ui.session.MsgBlock.Image
        }
        assertTrue("data-URL image must decode to an Image block on-device", hasImageBlock)

        // Binding exercises makeImageView (mounts the inline ImageView).
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val adapter = MessageAdapter()
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0) as MessageAdapter.MessageViewHolder
        holder.bind(rendered)
        assertTrue("bubble mounts an inline ImageView", hasImageView(holder.itemView as LinearLayout))
    }
}
