package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.PartContent

/**
 * Decoding the OpenCode server's SSE event envelope. Frames are real-shaped:
 * the global stream wraps the event in `payload`, the instance stream is flat.
 * Both must decode; unknown/`sync` events fall back to [ServerEvent.Other].
 * Mirrors iOS `ServerEventTests`.
 */
class ServerEventTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(s: String) = ServerEvent.decode(json, s)

    @Test
    fun decodesWrappedMessagePartDelta() {
        val event = decode(
            """
            {"directory":"/x","project":"p","payload":{"id":"evt_1","type":"message.part.delta",
            "properties":{"sessionID":"ses_1","messageID":"msg_1","partID":"prt_1","field":"text","delta":"Hel"}}}
            """.trimIndent(),
        )
        assertTrue(event is ServerEvent.PartDelta)
        val delta = event as ServerEvent.PartDelta
        assertEquals("ses_1", delta.sessionID)
        assertEquals("prt_1", delta.partID)
        assertEquals("text", delta.field)
        assertEquals("Hel", delta.delta)
    }

    @Test
    fun decodesFlatInstanceFrame() {
        val event = decode(
            """{"id":"evt_2","type":"message.part.delta","properties":{"sessionID":"s","messageID":"m","partID":"p","field":"text","delta":"x"}}""",
        )
        assertTrue(event is ServerEvent.PartDelta)
        assertEquals("x", (event as ServerEvent.PartDelta).delta)
    }

    @Test
    fun decodesMessageUpdatedAssistant() {
        val event = decode(
            """
            {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{
            "id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":2000},
            "modelID":"m","providerID":"p","agent":"build","cost":0,
            "tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
            """.trimIndent(),
        )
        assertTrue(event is ServerEvent.MessageUpdated)
        val updated = event as ServerEvent.MessageUpdated
        assertEquals("ses_1", updated.sessionID)
        val info = updated.info
        assertTrue(info is MessageInfo.Assistant)
        info as MessageInfo.Assistant
        assertEquals("build", info.agent)
        assertEquals(5, info.tokensOutput)
    }

    @Test
    fun decodesMessagePartUpdatedText() {
        val event = decode(
            """
            {"payload":{"type":"message.part.updated","properties":{"sessionID":"ses_1",
            "part":{"id":"prt_1","sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hello"},"time":1.0}}}
            """.trimIndent(),
        )
        assertTrue(event is ServerEvent.PartUpdated)
        val content = (event as ServerEvent.PartUpdated).part.content
        assertTrue(content is PartContent.Text)
        assertEquals("Hello", (content as PartContent.Text).text)
    }

    @Test
    fun syncPayloadIsIgnoredAsOther() {
        val event = decode("""{"payload":{"type":"sync","syncEvent":{"type":"message.updated.1"}}}""")
        assertTrue(event is ServerEvent.Other)
        assertEquals("sync", (event as ServerEvent.Other).type)
    }

    @Test
    fun unknownTypeIsOther() {
        val event = decode("""{"payload":{"type":"server.connected","properties":{}}}""")
        assertTrue(event is ServerEvent.Other)
        assertEquals("server.connected", (event as ServerEvent.Other).type)
    }
}
