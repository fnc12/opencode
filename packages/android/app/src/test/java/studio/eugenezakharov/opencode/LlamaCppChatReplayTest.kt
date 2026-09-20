package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.api.models.PartContent

/**
 * Replays the RAW `/session/:id/message` payload captured from the live relay for
 * the user's llama.cpp chat ("Что тут?") — WITHOUT any relay. The bytes are frozen
 * in a fixture so the test is deterministic even as the real project's data
 * changes over time. It drives the exact production decode path
 * (MessageParsing.parseMessageList) and the store (SessionStore.setInitial),
 * guarding against decode/render regressions on real-world data.
 *
 * See the memory note "capture-real-chat-data-for-tests".
 */
class LlamaCppChatReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/llamacpp_chat.json")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun decodesTheThreeMessagesWithTheirParts() {
        val messages = MessageParsing.parseMessageList(json, fixture())

        assertEquals("captured chat has 3 messages", 3, messages.size)

        // [0] the user's question.
        val user = messages[0]
        assertTrue("first is a user message", user.info is MessageInfo.User)
        val userText = user.parts.mapNotNull { (it.content as? PartContent.Text)?.text }.joinToString("")
        assertEquals("Что тут?", userText)
        assertTrue(user.hasRenderableContent)

        // [1] the assistant's tool turn: it read a file (a `read` tool, completed).
        val toolTurn = messages[1]
        assertTrue("second is an assistant message", toolTurn.info is MessageInfo.Assistant)
        val tools = toolTurn.parts.mapNotNull { it.content as? PartContent.Tool }
        assertTrue("assistant ran a read tool", tools.any { it.tool == "read" })
        assertTrue("assistant reasoned", toolTurn.parts.any { it.content is PartContent.Reasoning })
        assertTrue("the tool turn renders (tool row)", toolTurn.hasRenderableContent)

        // [2] the assistant's answer.
        val answer = messages[2]
        assertTrue("third is an assistant message", answer.info is MessageInfo.Assistant)
        val answerText = answer.parts.mapNotNull { (it.content as? PartContent.Text)?.text }.joinToString("")
        assertTrue("answer describes llama.cpp", answerText.contains("llama.cpp"))
        assertTrue(answer.hasRenderableContent)
    }

    @Test
    fun replaysThroughTheStoreLikeAnInitialLoad() {
        val messages = MessageParsing.parseMessageList(json, fixture())
        val store = SessionStore()
        store.setInitial(messages)

        assertEquals(3, store.messages.size)
        // Every message in this chat renders something (no empty bubbles).
        assertTrue("all three render", store.messages.all { it.hasRenderableContent })
        assertEquals(2, store.messages.count { it.info is MessageInfo.Assistant })
        assertEquals(1, store.messages.count { it.info is MessageInfo.User })
    }
}
