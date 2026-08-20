package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts

/**
 * Covers the remaining SessionStore.apply reducer branches + prependOlder/dismiss/
 * setRevert not exercised by SessionStoreTest.
 */
class SessionStoreReducerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sid = "ses_1"
    private fun event(s: String) = ServerEvent.decode(json, s)!!
    private fun store() = SessionStore()

    private fun addAssistant(store: SessionStore, id: String) = store.apply(
        event("""{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"$id","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}"""), sid,
    )

    @Test fun onChangeGetterAndStreamStatusEntries() {
        val s = store()
        var fired = 0
        val cb: () -> Unit = { fired++ }
        s.onChange = cb
        assertSame(cb, s.onChange) // read the getter back
        addAssistant(s, "m1")
        assertTrue("onChange fires on a state change", fired > 0)
        // The enum's entries accessor (all four stream states are present).
        assertEquals(4, SessionStore.StreamStatus.entries.size)
    }

    @Test fun messageRemoved() {
        val s = store(); addAssistant(s, "m1")
        assertEquals(1, s.messages.size)
        s.apply(event("""{"type":"message.removed","properties":{"sessionID":"ses_1","messageID":"m1"}}"""), sid)
        assertEquals(0, s.messages.size)
    }

    @Test fun partRemoved() {
        val s = store(); addAssistant(s, "m1")
        s.apply(event("""{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","sessionID":"ses_1","messageID":"m1","type":"text","text":"hi"}}}"""), sid)
        assertEquals(1, s.messages.first().parts.size)
        s.apply(event("""{"type":"message.part.removed","properties":{"sessionID":"ses_1","messageID":"m1","partID":"p1"}}"""), sid)
        assertEquals(0, s.messages.first().parts.size)
    }

    @Test fun permissionAskedThenReplied() {
        val s = store()
        s.apply(event("""{"type":"permission.v2.asked","properties":{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["ls"]}}"""), sid)
        assertEquals(1, s.pendingPermissions.size)
        s.apply(event("""{"type":"permission.v2.replied","properties":{"sessionID":"ses_1","requestID":"per_1"}}"""), sid)
        assertEquals(0, s.pendingPermissions.size)
    }

    @Test fun questionAskedThenReplied() {
        val s = store()
        s.apply(event("""{"type":"question.v2.asked","properties":{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Q","header":"H","options":[{"label":"A","description":"a"}]}]}}"""), sid)
        assertEquals(1, s.pendingQuestions.size)
        s.apply(event("""{"type":"question.v2.replied","properties":{"sessionID":"ses_1","requestID":"que_1"}}"""), sid)
        assertEquals(0, s.pendingQuestions.size)
    }

    @Test fun foreignSessionIgnored() {
        val s = store()
        s.apply(event("""{"type":"message.updated","properties":{"sessionID":"OTHER","info":{"id":"x","sessionID":"OTHER","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}"""), sid)
        assertEquals(0, s.messages.size)
    }

    @Test fun prependOlderDedupes() {
        val s = store()
        s.setInitial(listOf(MessageWithParts(MessageInfo.Assistant("m2", sid, 2.0), mutableListOf())))
        s.prependOlder(listOf(
            MessageWithParts(MessageInfo.Assistant("m1", sid, 1.0), mutableListOf()),
            MessageWithParts(MessageInfo.Assistant("m2", sid, 2.0), mutableListOf()), // dup → dropped
        ))
        assertEquals(2, s.messages.size)
        assertEquals("m1", s.messages.first().id) // older sorts first
    }

    @Test fun dismissAndRevert() {
        val s = store()
        s.setInitialPermissions(listOf(studio.eugenezakharov.opencode.api.models.PermissionRequest("per_9", sid, "bash", listOf("x"))))
        s.dismissPermission("per_9")
        assertTrue(s.pendingPermissions.isEmpty())
        s.setRevert("msg_5")
        assertEquals("msg_5", s.revertMessageID)
        s.setRevert(null)
        assertNull(s.revertMessageID)
    }

    @Test fun partDeltaAppendsStreamedText() {
        val s = store(); addAssistant(s, "m1")
        s.apply(event("""{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","sessionID":"ses_1","messageID":"m1","type":"text","text":"Hel"}}}"""), sid)
        s.apply(event("""{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"m1","partID":"p1","field":"text","delta":"lo"}}"""), sid)
        val text = (s.messages.first().parts.first().content as? studio.eugenezakharov.opencode.api.models.PartContent.Text)?.text
        assertEquals("Hello", text)
    }

    @Test fun partDeltaSynthesizesPartWhenAbsent() {
        // A delta whose part hasn't been seen yet → the store synthesizes a text part.
        val s = store(); addAssistant(s, "m1")
        s.apply(event("""{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"m1","partID":"new_p","field":"text","delta":"hi"}}"""), sid)
        val part = s.messages.first().parts.firstOrNull()
        assertEquals("new_p", part?.id)
        assertEquals("hi", (part?.content as? studio.eugenezakharov.opencode.api.models.PartContent.Text)?.text)
    }

    @Test fun partDeltaForUnknownMessageIsIgnored() {
        val s = store(); addAssistant(s, "m1")
        s.apply(event("""{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"NOPE","partID":"p","field":"text","delta":"x"}}"""), sid)
        assertTrue(s.messages.first().parts.isEmpty())
    }

    @Test fun partDeltaIgnoresNonTextField() {
        val s = store(); addAssistant(s, "m1")
        s.apply(event("""{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","sessionID":"ses_1","messageID":"m1","type":"text","text":"x"}}}"""), sid)
        s.apply(event("""{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"m1","partID":"p1","field":"reasoning","delta":"ignored"}}"""), sid)
        val text = (s.messages.first().parts.first().content as? studio.eugenezakharov.opencode.api.models.PartContent.Text)?.text
        assertEquals("x", text) // non-text delta is dropped
    }

    @Test fun todoUpdatedReplacesList() {
        val s = store()
        s.apply(event("""{"type":"todo.updated","properties":{"sessionID":"ses_1","todos":[{"content":"a","status":"pending","priority":"high"},{"content":"b","status":"completed","priority":"low"}]}}"""), sid)
        assertEquals(2, s.todos.size)
    }

    @Test fun sessionUpdatedSetsRevert() {
        val s = store()
        s.apply(event("""{"type":"session.updated","properties":{"info":{"id":"ses_1","revert":{"messageID":"m_9"}}}}"""), sid)
        assertEquals("m_9", s.revertMessageID)
    }

    @Test fun foreignSessionEventsAreIgnoredAcrossTypes() {
        val s = store(); addAssistant(s, "m1")
        // Each of these targets OTHER → the `else false` branch, no revision bump.
        s.apply(event("""{"type":"message.part.updated","properties":{"sessionID":"OTHER","part":{"id":"p","sessionID":"OTHER","messageID":"m1","type":"text","text":"x"}}}"""), sid)
        s.apply(event("""{"type":"todo.updated","properties":{"sessionID":"OTHER","todos":[{"content":"a","status":"pending","priority":"high"}]}}"""), sid)
        s.apply(event("""{"type":"session.updated","properties":{"info":{"id":"OTHER","revert":{"messageID":"z"}}}}"""), sid)
        // The remaining reducer arms' `else false` (foreign session) branches:
        s.apply(event("""{"type":"message.part.delta","properties":{"sessionID":"OTHER","messageID":"m1","partID":"p","field":"text","delta":"x"}}"""), sid)
        s.apply(event("""{"type":"message.part.removed","properties":{"sessionID":"OTHER","messageID":"m1","partID":"p"}}"""), sid)
        s.apply(event("""{"type":"message.removed","properties":{"sessionID":"OTHER","messageID":"m1"}}"""), sid)
        s.apply(event("""{"type":"permission.v2.replied","properties":{"sessionID":"OTHER","requestID":"per_1"}}"""), sid)
        s.apply(event("""{"type":"question.v2.replied","properties":{"sessionID":"OTHER","requestID":"que_1"}}"""), sid)
        // An unrecognized event type → the terminal `else -> false`.
        s.apply(event("""{"type":"server.connected","properties":{}}"""), sid)
        assertEquals(0, s.messages.first().parts.size)
        assertEquals(1, s.messages.size) // foreign message.removed didn't drop ours
        assertTrue(s.todos.isEmpty())
        assertNull(s.revertMessageID)
    }

    @Test fun setStatusChangesAndDedupes() {
        val s = store()
        var changes = 0
        s.onChange = { changes++ }
        s.setStatus(SessionStore.StreamStatus.LIVE)
        assertEquals(SessionStore.StreamStatus.LIVE, s.status)
        assertEquals(1, changes)
        s.setStatus(SessionStore.StreamStatus.LIVE) // same value → no-op, no callback
        assertEquals(1, changes)
        s.setStatus(SessionStore.StreamStatus.RECONNECTING)
        assertEquals(2, changes)
    }
}
