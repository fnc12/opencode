package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore

/**
 * Decoding question events (#28). Mirrors the iOS `QuestionDecodeTests` and the
 * Android `PermissionDecodeTest`. The global stream wraps the event in
 * `payload`; the instance stream is flat. For `question.v2.asked` the
 * `properties` ARE the request (with nested `questions`/`options`).
 */
class QuestionDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(s: String) = ServerEvent.decode(json, s)

    @Test
    fun decodesQuestionAsked() {
        val event = decode(
            """
            {"directory":"/x","project":"p","payload":{"id":"evt_1","type":"question.v2.asked",
            "properties":{"id":"q_1","sessionID":"ses_1","questions":[
              {"question":"Which database?","header":"Pick one","multiple":false,"custom":false,"options":[
                {"label":"Option A","description":"the first"},
                {"label":"Option B","description":"the second"}]}]}}}
            """.trimIndent(),
        )
        assertTrue(event is ServerEvent.QuestionAsked)
        val req = (event as ServerEvent.QuestionAsked).request
        assertEquals("q_1", req.id)
        assertEquals("ses_1", req.sessionID)
        assertEquals(1, req.questions.size)
        val q = req.questions.first()
        assertEquals("Which database?", q.question)
        assertEquals("Pick one", q.header)
        assertFalse(q.allowsMultiple)
        assertEquals(listOf("Option A", "Option B"), q.options.map { it.label })
        assertEquals("the first", q.options.first().description)
    }

    @Test
    fun decodesMultiSelectQuestion() {
        val event = decode(
            """{"type":"question.v2.asked","properties":{"id":"q_2","sessionID":"s","questions":[
               {"question":"Pick langs","header":"","multiple":true,"options":[
                 {"label":"Kotlin","description":""},{"label":"Swift","description":""}]}]}}""",
        )
        assertTrue(event is ServerEvent.QuestionAsked)
        val q = (event as ServerEvent.QuestionAsked).request.questions.first()
        assertTrue(q.allowsMultiple)
        assertEquals("", q.header)
    }

    @Test
    fun decodesQuestionReplied() {
        val event = decode(
            """{"payload":{"type":"question.v2.replied","properties":{"sessionID":"ses_1","requestID":"q_1"}}}""",
        )
        assertTrue(event is ServerEvent.QuestionResolved)
        event as ServerEvent.QuestionResolved
        assertEquals("ses_1", event.sessionID)
        assertEquals("q_1", event.requestID)
    }

    @Test
    fun decodesQuestionRejected() {
        val event = decode(
            """{"type":"question.v2.rejected","properties":{"sessionID":"ses_1","requestID":"q_1"}}""",
        )
        assertTrue(event is ServerEvent.QuestionResolved)
        assertEquals("q_1", (event as ServerEvent.QuestionResolved).requestID)
    }
}

/**
 * Folding question events into the session's pending list (#28): add/dedup/
 * remove (on both replied and rejected), filter by session, and optimistic
 * dismiss. Mirrors the Android `PermissionStoreTest`.
 */
class QuestionStoreTest {
    private val sessionID = "ses_1"
    private val json = Json { ignoreUnknownKeys = true }
    private fun event(s: String) = ServerEvent.decode(json, s)!!

    private fun asked(id: String, session: String) = event(
        """{"type":"question.v2.asked","properties":{"id":"$id","sessionID":"$session","questions":[
           {"question":"Q?","header":"H","options":[{"label":"A","description":""}]}]}}""",
    )

    private fun replied(id: String, session: String) = event(
        """{"type":"question.v2.replied","properties":{"sessionID":"$session","requestID":"$id"}}""",
    )

    private fun rejected(id: String, session: String) = event(
        """{"type":"question.v2.rejected","properties":{"sessionID":"$session","requestID":"$id"}}""",
    )

    @Test
    fun askedAddsThenRepliedRemoves() {
        val store = SessionStore()
        store.apply(asked("q_1", sessionID), sessionID)
        assertEquals(listOf("q_1"), store.pendingQuestions.map { it.id })
        store.apply(replied("q_1", sessionID), sessionID)
        assertTrue(store.pendingQuestions.isEmpty())
    }

    @Test
    fun rejectedAlsoRemoves() {
        val store = SessionStore()
        store.apply(asked("q_1", sessionID), sessionID)
        store.apply(rejected("q_1", sessionID), sessionID)
        assertTrue(store.pendingQuestions.isEmpty())
    }

    @Test
    fun deduplicatesSameRequest() {
        val store = SessionStore()
        store.apply(asked("q_1", sessionID), sessionID)
        store.apply(asked("q_1", sessionID), sessionID)
        assertEquals(1, store.pendingQuestions.size)
    }

    @Test
    fun ignoresOtherSessions() {
        val store = SessionStore()
        store.apply(asked("q_1", "OTHER"), sessionID)
        assertTrue(store.pendingQuestions.isEmpty())
    }

    @Test
    fun dismissRemovesOptimistically() {
        val store = SessionStore()
        store.apply(asked("q_1", sessionID), sessionID)
        store.dismissQuestion("q_1")
        assertTrue(store.pendingQuestions.isEmpty())
    }

    @Test
    fun seedsInitialQuestions() {
        val store = SessionStore()
        store.apply(asked("q_1", sessionID), sessionID)
        store.setInitialQuestions(emptyList())
        assertTrue(store.pendingQuestions.isEmpty())
    }
}
