package studio.eugenezakharov.opencode

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.AgentInfo

/** Decode + the `selectable` rule for AgentInfo (from GET /agent). */
class AgentInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodesAndComputesSelectable() {
        val agents = json.decodeFromString(
            ListSerializer(AgentInfo.serializer()),
            """[
              {"name":"build","description":"Primary","mode":"primary","hidden":false},
              {"name":"sub","mode":"subagent"},
              {"name":"secret","mode":"primary","hidden":true},
              {"name":"plan"}
            ]""",
        )
        assertEquals(4, agents.size)
        assertEquals("build", agents[0].name)
        assertEquals("Primary", agents[0].description)
        assertTrue("a visible primary agent is selectable", agents[0].selectable)
        assertFalse("subagents aren't selectable", agents[1].selectable)
        assertFalse("hidden agents aren't selectable", agents[2].selectable)
        // Defaults: mode=primary, hidden=false → selectable.
        assertTrue("defaulted agent is selectable", agents[3].selectable)
    }
}
