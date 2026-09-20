package studio.eugenezakharov.opencode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.ui.session.MessageAdapter

/**
 * Guards the "empty You bubble" regression: a message whose only part is
 * synthetic/ignored (or empty) is not renderable, so the adapter skips it.
 * See [MessageWithParts.hasRenderableContent].
 */
class MessageRenderingTest {
    private fun user(vararg parts: MessagePart) =
        MessageWithParts(MessageInfo.User("m", "s", 1.0), parts.toMutableList())
    private fun assistant(vararg parts: MessagePart) =
        MessageWithParts(MessageInfo.Assistant("m", "s", 1.0), parts.toMutableList())

    private fun part(content: PartContent?, synthetic: Boolean = false, ignored: Boolean = false) =
        MessagePart("p", "s", "m", "x", content, synthetic, ignored)

    @Test fun syntheticOnlyMessageIsHidden() {
        // The exact filler POST /session/:id/shell posts as a user message.
        assertFalse(user(part(PartContent.Text("The following tool was executed by the user"), synthetic = true)).hasRenderableContent)
    }

    @Test fun ignoredOnlyMessageIsHidden() {
        assertFalse(user(part(PartContent.Text("hidden"), ignored = true)).hasRenderableContent)
    }

    @Test fun emptyTextMessageIsHidden() {
        assertFalse(assistant(part(PartContent.Text("   "))).hasRenderableContent)
    }

    @Test fun realTextMessageIsVisible() {
        assertTrue(user(part(PartContent.Text("hello"))).hasRenderableContent)
    }

    @Test fun toolOnlyMessageIsVisible() {
        assertTrue(assistant(part(PartContent.Tool(tool = "bash", callID = "c", status = "completed"))).hasRenderableContent)
    }

    @Test fun syntheticTextButRealToolIsVisible() {
        assertTrue(
            assistant(
                part(PartContent.Text("filler"), synthetic = true),
                part(PartContent.Tool(tool = "bash", callID = "c", status = "completed")),
            ).hasRenderableContent,
        )
    }

    // Image-attachment gating (the pure-Kotlin guard paths; the actual Bitmap
    // decode is exercised on-device). A non-image file part must NOT be treated
    // as an inline image — it falls back to the "📎 filename" chip.

    @Test fun nonImageFileIsNotAnAttachmentImage() {
        val file = PartContent.FileRef(filename = "main.kt", url = "file:///main.kt", mime = "text/plain")
        assertNull(MessageAdapter.imageAttachment(file))
    }

    @Test fun fileWithoutUrlIsNotAnAttachmentImage() {
        val file = PartContent.FileRef(filename = "shot.png", url = null, mime = "image/png")
        assertNull(MessageAdapter.imageAttachment(file))
    }

    @Test fun malformedDataUrlDecodesToNull() {
        // No scheme / no ;base64 marker — rejected before any decode.
        assertNull(MessageAdapter.decodeDataUrlImage("https://example.com/a.png"))
        assertNull(MessageAdapter.decodeDataUrlImage("data:image/png,rawnotbase64"))
    }
}
