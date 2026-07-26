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
 * Running-tools extraction against the REAL captured payload (wifi-densepose,
 * 2026-07-26): an unfinished assistant turn with completed bash/read tools and
 * one RUNNING `question` tool. Mirrors iOS `RunningToolsTests`.
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
        assertEquals("only the running tool, not completed ones", 1, running.size)
        assertEquals("question", running.first().name)
        assertNotNull("start time drives the elapsed label", running.first().startedMs)
    }

    @Test
    fun completedTurnHasNoRunningTools() {
        val completedOnly = fixture().filter { m ->
            (m.info as? MessageInfo.Assistant)?.completed != null || m.info !is MessageInfo.Assistant
        }
        assertTrue(RunningTools.extract(completedOnly).isEmpty())
    }
}
