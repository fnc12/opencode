package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.AgentInfo
import studio.eugenezakharov.opencode.api.models.CommandInfo
import studio.eugenezakharov.opencode.api.models.FileEntry
import studio.eugenezakharov.opencode.api.models.HealthResponse
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.ProviderAuthMethod
import studio.eugenezakharov.opencode.api.models.ProvidersParsing
import studio.eugenezakharov.opencode.api.models.QuestionItem
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionFileDiff
import studio.eugenezakharov.opencode.api.models.TodoItem

/**
 * Reads EVERY field of every wire model at least once. These are pure data
 * carriers whose behaviour is already exercised elsewhere by inference, but a
 * getter is only counted as covered when it is actually read — so decode a
 * fully-populated instance of each and touch every accessor.
 */
class ModelsExhaustiveTest {
    private val json = Json { ignoreUnknownKeys = true }
    private inline fun <reified T> decode(s: String): T = json.decodeFromString(s)

    @Test fun sessionAllOptionalFieldsPresent() {
        val s = decode<Session>(
            """{"id":"ses_1","slug":"my-slug","projectID":"p","directory":"/w","parentID":"ses_0",
                "title":"T","version":"9","time":{"created":1.0,"updated":2.0,"compacting":3.0,"archived":4.0},
                "summary":{"additions":5,"deletions":6,"files":7},"share":{"url":"https://x"},
                "revert":{"messageID":"m_1"}}""",
        )
        assertEquals("ses_1", s.id)
        assertEquals("my-slug", s.slug)
        assertEquals("ses_0", s.parentID)
        assertEquals("9", s.version)
        assertEquals(3.0, s.time.compacting!!, 0.0)
        assertEquals(4.0, s.time.archived!!, 0.0)
        assertEquals(1.0, s.time.created, 0.0)
        assertEquals(5, s.summary!!.additions)
        assertEquals(6, s.summary!!.deletions)
        assertEquals("https://x", s.share!!.url)
        assertEquals("m_1", s.revert!!.messageID)
    }

    @Test fun projectAllFields() {
        val p = decode<Project>(
            """{"id":"p","worktree":"/w","vcs":"git","name":"proj",
                "time":{"created":1.0,"updated":2.0,"initialized":3.0},"sandboxes":["a","b"]}""",
        )
        assertEquals("git", p.vcs)
        assertEquals(2, p.sandboxes.size)
        assertEquals(1.0, p.time.created, 0.0)
        assertEquals(2.0, p.time.updated, 0.0)
        assertEquals(3.0, p.time.initialized!!, 0.0)
    }

    @Test fun fileEntryAllFields() {
        val f = decode<FileEntry>("""{"name":"d","path":"a/d","absolute":"/a/d","type":"directory","ignored":true}""")
        assertEquals("directory", f.type)
        assertEquals("a/d", f.path)
        assertTrue(f.ignored!!)
        assertTrue(f.isDirectory)
    }

    @Test fun smallModels() {
        val c = decode<CommandInfo>("""{"name":"init","description":"seed"}""")
        assertEquals("init", c.name)
        assertEquals("seed", c.description)

        val auth = decode<ProviderAuthMethod>("""{"type":"api","label":"API key"}""")
        assertEquals("API key", auth.label)

        val todo = decode<TodoItem>("""{"content":"x","status":"pending","priority":"high"}""")
        assertEquals("high", todo.priority)
        assertFalse(todo.done)

        val agent = decode<AgentInfo>("""{"name":"plan","mode":"subagent","hidden":true}""")
        assertEquals("subagent", agent.mode)
        assertTrue(agent.hidden)
        assertFalse(agent.selectable)

        val h = decode<HealthResponse>("""{"healthy":true,"version":"1.2.3"}""")
        assertTrue(h.healthy)
        assertEquals("1.2.3", h.version)

        val diff = decode<SessionFileDiff>("""{"file":"a.kt","patch":"@@","additions":3,"deletions":1,"status":"modified"}""")
        assertEquals("a.kt", diff.file)
        assertEquals("modified", diff.status)
    }

    @Test fun modelInfoNameReadViaProviders() {
        val providers = ProvidersParsing.parse(
            json,
            """{"providers":[{"id":"anthropic","name":"Anthropic","models":{"claude":{"id":"claude","name":"Claude"}}}]}""",
        )
        val model = providers.single().models.single()
        assertEquals("Claude", model.name)
        assertEquals("Claude", model.displayName)
    }

    @Test fun questionItemOptionalFlags() {
        val obj = json.parseToJsonElement(
            """{"question":"Pick","header":"H","options":[{"label":"A","description":"first"}],
                "multiple":true,"custom":false}""",
        ).jsonObject
        val item = QuestionItem.from(obj)!!
        assertTrue(item.multiple!!)
        assertFalse(item.custom!!)
        assertTrue(item.allowsMultiple)
        assertEquals("first", item.options.single().description)
    }

    @Test fun userAndAssistantMessageInfoFields() {
        val messages = MessageParsing.parseMessageList(
            json,
            """[
              {"info":{"id":"m1","sessionID":"ses_1","role":"user","time":{"created":1.0},"agent":"build"},
               "parts":[{"id":"p1","sessionID":"ses_1","messageID":"m1","type":"text","text":"hi","synthetic":true,"ignored":true}]},
              {"info":{"id":"m2","sessionID":"ses_1","role":"assistant","time":{"created":2.0,"completed":3.0},
                       "modelID":"claude","providerID":"anthropic","cost":0.5,
                       "error":{"name":"E","data":{"message":"boom"}}},
               "parts":[{"id":"p2","sessionID":"ses_1","messageID":"m2","type":"tool","callID":"c1","tool":"bash",
                         "state":{"status":"completed"}},
                        {"id":"p3","sessionID":"ses_1","messageID":"m2","type":"step-finish","reason":"stop"}]}
            ]""",
        )
        val user = messages[0].info as MessageInfo.User
        assertEquals("ses_1", user.sessionID)
        assertEquals("user", user.role)
        assertEquals("build", user.agent)

        val part = messages[0].parts.single()
        assertEquals("text", part.type)
        assertEquals("ses_1", part.sessionID)
        assertTrue(part.synthetic)
        assertTrue(part.ignored)
        assertFalse(part.isVisible)

        val assistant = messages[1].info as MessageInfo.Assistant
        assertEquals("ses_1", assistant.sessionID)
        assertEquals("assistant", assistant.role)
        assertEquals("claude", assistant.modelID)
        assertEquals("anthropic", assistant.providerID)
        assertEquals(0.5, assistant.cost, 0.0)
        assertEquals("E", assistant.error!!.name)
        assertEquals("boom", assistant.error!!.message)
        assertEquals("boom", assistant.error!!.displayText)

        val tool = messages[1].parts[0].content as PartContent.Tool
        assertEquals("c1", tool.callID)
        val stepFinish = messages[1].parts[1].content as PartContent.StepFinish
        assertEquals("stop", stepFinish.reason)
    }
}
