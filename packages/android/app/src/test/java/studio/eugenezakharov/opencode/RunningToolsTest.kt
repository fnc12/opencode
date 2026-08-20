package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.RunningTools
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageParsing

/**
 * Running-tools extraction against the captured wifi-densepose payload
 * (2026-07-26), enriched with a completed `read` and a running `bash`: the
 * "background processes" strip must list ONLY the running background tool
 * (bash) — excluding completed tools and the `question` tool (that one is
 * driven by the question dock, so it must not also appear in the strip).
 * Mirrors iOS `RunningToolsTests`.
 */
class RunningToolsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture() = MessageParsing.parseMessageList(
        json,
        javaClass.getResource("/fixtures/running-tool.json")!!.readText(),
    )

    @Test
    fun extractsOnlyRunningToolsFromUnfinishedTurn() {
        val running = RunningTools.extract(fixture())
        assertEquals("only the running background tool — not completed ones, not the question", 1, running.size)
        assertEquals("bash", running.first().name)
        assertNotNull("start time drives the elapsed label", running.first().startedMs)
    }

    @Test
    fun questionToolIsExcludedFromStrip() {
        // The `question` tool "runs" while waiting on the user; it belongs to the
        // question dock, not the running-processes strip (else it double-shows).
        assertTrue(
            "the running question must not appear in the strip",
            RunningTools.extract(fixture()).none { it.name == "question" },
        )
    }

    @Test
    fun completedTurnHasNoRunningTools() {
        val completedOnly = fixture().filter { m ->
            (m.info as? MessageInfo.Assistant)?.completed != null || m.info !is MessageInfo.Assistant
        }
        assertTrue(RunningTools.extract(completedOnly).isEmpty())
    }
}
