package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.PartContent

/**
 * Folding a stream of events into conversation state: upserts, ordering by
 * creation time, streaming text deltas, snapshot reconciliation, and filtering
 * by session. Mirrors iOS `SessionStoreTests`.
 */
class SessionStoreTest {
    private val sessionID = "ses_1"
    private val json = Json { ignoreUnknownKeys = true }
    private fun event(s: String) = ServerEvent.decode(json, s)!!

    private fun userUpdated(id: String, created: Double) = event(
        """{"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"$id","sessionID":"ses_1","role":"user","time":{"created":$created}}}}""",
    )

    private fun assistantUpdated(id: String, created: Double) = event(
        """{"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"$id","sessionID":"ses_1","role":"assistant","time":{"created":$created},
        "modelID":"m","providerID":"p","agent":"build","cost":0,
        "tokens":{"input":1,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}}}""",
    )

    private fun assistantCompleted(id: String, created: Double, completed: Double) = event(
        """{"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"$id","sessionID":"ses_1","role":"assistant","time":{"created":$created,"completed":$completed},
        "modelID":"m","providerID":"p","agent":"build","cost":0,
        "tokens":{"input":1,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}}}""",
    )

    private fun textDelta(messageID: String, partID: String, delta: String) = event(
        """{"type":"message.part.delta","properties":{"sessionID":"ses_1",
        "messageID":"$messageID","partID":"$partID","field":"text","delta":"$delta"}}""",
    )

    private fun textPart(messageID: String, partID: String, text: String) = event(
        """{"type":"message.part.updated","properties":{"sessionID":"ses_1",
        "part":{"id":"$partID","sessionID":"ses_1","messageID":"$messageID","type":"text","text":"$text"},"time":1}}""",
    )

    private fun assistantText(store: SessionStore): String {
        val sb = StringBuilder()
        for (m in store.messages) {
            if (m.info is MessageInfo.Assistant) {
                for (p in m.parts) (p.content as? PartContent.Text)?.let { sb.append(it.text) }
            }
        }
        return sb.toString()
    }

    @Test
    fun streamsAndOrders() {
        val store = SessionStore()
        store.setInitial(emptyList())

        // Arrive out of order: assistant first, then the earlier user message.
        store.apply(assistantUpdated("msg_a", 2000.0), sessionID)
        store.apply(userUpdated("msg_u", 1000.0), sessionID)
        store.apply(textPart("msg_u", "prt_u", "Hi"), sessionID)
        store.apply(textDelta("msg_a", "prt_a", "Hello"), sessionID)
        store.apply(textDelta("msg_a", "prt_a", " world"), sessionID)

        assertEquals(2, store.messages.size)
        assertEquals("msg_u", store.messages.first().id)
        assertEquals("msg_a", store.messages.last().id)
        assertEquals("Hello world", assistantText(store))
    }

    @Test
    fun snapshotReconcilesDeltaBuiltText() {
        val store = SessionStore()
        store.setInitial(emptyList())
        store.apply(assistantUpdated("msg_a", 1.0), sessionID)
        store.apply(textDelta("msg_a", "prt_a", "Hel"), sessionID)
        store.apply(textPart("msg_a", "prt_a", "Hello, world"), sessionID)
        assertEquals("Hello, world", assistantText(store))
    }

    @Test
    fun ignoresOtherSessions() {
        val store = SessionStore()
        store.setInitial(emptyList())
        store.apply(assistantUpdated("msg_a", 1.0), "DIFFERENT")
        assertTrue(store.messages.isEmpty())
    }

    @Test
    fun isBusyTracksAssistantCompletion() {
        val store = SessionStore()
        store.setInitial(emptyList())
        assertFalse(store.isBusy) // no messages

        // Assistant message still generating (no time.completed) → busy.
        store.apply(assistantUpdated("msg_a", 1.0), sessionID)
        assertTrue(store.isBusy)

        // Same message id with time.completed set → no longer busy.
        store.apply(assistantCompleted("msg_a", 1.0, 2.0), sessionID)
        assertFalse(store.isBusy)
    }

    @Test
    fun revisionBumpsOnAppliedChange() {
        val store = SessionStore()
        store.setInitial(emptyList())
        val before = store.revision
        store.apply(userUpdated("msg_u", 1.0), sessionID)
        assertTrue(store.revision > before)
    }
}
