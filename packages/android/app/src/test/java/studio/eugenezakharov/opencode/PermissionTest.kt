package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore

/**
 * Decoding permission events. Mirrors iOS `PermissionDecodeTests`. The global
 * stream wraps the event in `payload`; the instance stream is flat. For
 * `permission.v2.asked` the `properties` ARE the request.
 */
class PermissionDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(s: String) = ServerEvent.decode(json, s)

    @Test
    fun decodesPermissionAsked() {
        val event = decode(
            """
            {"directory":"/x","project":"p","payload":{"id":"evt_1","type":"permission.v2.asked",
            "properties":{"id":"perm_1","sessionID":"ses_1","action":"bash","resources":["echo hi"],"metadata":{}}}}
            """.trimIndent(),
        )
        assertTrue(event is ServerEvent.PermissionAsked)
        val req = (event as ServerEvent.PermissionAsked).request
        assertEquals("perm_1", req.id)
        assertEquals("ses_1", req.sessionID)
        assertEquals("bash", req.action)
        assertEquals(listOf("echo hi"), req.resources)
        assertEquals("Run command: echo hi", req.summary)
    }

    @Test
    fun decodesPermissionReplied() {
        val event = decode(
            """{"payload":{"type":"permission.v2.replied","properties":{"sessionID":"ses_1","requestID":"perm_1","reply":"once"}}}""",
        )
        assertTrue(event is ServerEvent.PermissionReplied)
        event as ServerEvent.PermissionReplied
        assertEquals("ses_1", event.sessionID)
        assertEquals("perm_1", event.requestID)
    }

    @Test
    fun decodesFlatInstanceFrame() {
        val event = decode(
            """{"type":"permission.v2.asked","properties":{"id":"p1","sessionID":"s","action":"edit","resources":["a.kt"]}}""",
        )
        assertTrue(event is ServerEvent.PermissionAsked)
        assertEquals("Modify file: a.kt", (event as ServerEvent.PermissionAsked).request.summary)
    }
}

/**
 * Folding permission events into the session's pending list: add/dedup/remove,
 * filter by session, and optimistic dismiss. Mirrors iOS `PermissionStoreTests`.
 */
class PermissionStoreTest {
    private val sessionID = "ses_1"
    private val json = Json { ignoreUnknownKeys = true }
    private fun event(s: String) = ServerEvent.decode(json, s)!!

    private fun asked(id: String, session: String) = event(
        """{"type":"permission.v2.asked","properties":{"id":"$id","sessionID":"$session","action":"bash","resources":["x"]}}""",
    )

    private fun replied(id: String, session: String) = event(
        """{"type":"permission.v2.replied","properties":{"sessionID":"$session","requestID":"$id","reply":"once"}}""",
    )

    @Test
    fun askedAddsThenRepliedRemoves() {
        val store = SessionStore()
        store.apply(asked("perm_1", sessionID), sessionID)
        assertEquals(listOf("perm_1"), store.pendingPermissions.map { it.id })
        store.apply(replied("perm_1", sessionID), sessionID)
        assertTrue(store.pendingPermissions.isEmpty())
    }

    @Test
    fun deduplicatesSameRequest() {
        val store = SessionStore()
        store.apply(asked("perm_1", sessionID), sessionID)
        store.apply(asked("perm_1", sessionID), sessionID)
        assertEquals(1, store.pendingPermissions.size)
    }

    @Test
    fun ignoresOtherSessions() {
        val store = SessionStore()
        store.apply(asked("perm_1", "OTHER"), sessionID)
        assertTrue(store.pendingPermissions.isEmpty())
    }

    @Test
    fun dismissRemovesOptimistically() {
        val store = SessionStore()
        store.apply(asked("perm_1", sessionID), sessionID)
        store.dismissPermission("perm_1")
        assertTrue(store.pendingPermissions.isEmpty())
    }

    @Test
    fun seedsInitialPermissions() {
        val store = SessionStore()
        store.apply(asked("perm_1", sessionID), sessionID)
        store.setInitialPermissions(emptyList())
        assertTrue(store.pendingPermissions.isEmpty())
    }
}
