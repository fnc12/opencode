package studio.eugenezakharov.opencode

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.ui.session.loadAttachment
import java.io.File

/**
 * Instrumented coverage for loadAttachment — decodes a picked image Uri into a
 * thumbnail bitmap + a JPEG data-URL attachment. Driven with a real file:// Uri
 * (the paste-image UI path can't easily seed the clipboard with a content Uri).
 */
@RunWith(AndroidJUnit4::class)
class LoadAttachmentInstrumentedTest {
    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun imageUri(): Uri {
        val bmp = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val file = File(ctx().cacheDir, "attach-test.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test fun decodesImageUriToAttachment() = runBlocking {
        val result = loadAttachment(ctx(), imageUri())
        assertTrue("a valid image decodes to a (bitmap, attachment) pair", result != null)
        assertTrue("attachment is a JPEG data URL", result!!.second.url.startsWith("data:image/jpeg;base64,"))
        assertTrue("bitmap has real dimensions", result.first.width > 0 && result.first.height > 0)
    }

    @Test fun returnsNullForUndecodableData() {
        // A non-image file → BitmapFactory returns null → loadAttachment returns null.
        val file = File(ctx().cacheDir, "not-an-image.txt").apply { writeText("hello, not an image") }
        val result = runBlocking { loadAttachment(ctx(), Uri.fromFile(file)) }
        assertNull("non-image data → null", result)
    }
}
