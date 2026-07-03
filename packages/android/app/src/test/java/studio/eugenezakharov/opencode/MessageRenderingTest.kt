package studio.eugenezakharov.opencode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent

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
}
