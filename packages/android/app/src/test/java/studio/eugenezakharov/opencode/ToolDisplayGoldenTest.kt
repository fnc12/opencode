package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.api.models.ToolMeta
import studio.eugenezakharov.opencode.ui.session.FileRefDisplay
import studio.eugenezakharov.opencode.ui.session.PatchDisplay
import studio.eugenezakharov.opencode.ui.session.ToolDisplay

/**
 * Golden tests over a REAL session captured from a live OpenCode server
 * (sqlite2orm, fetched via the relay; sanitized to structure only). They assert
 * the Android client renders tool / patch / file parts the same compact way the
 * web client does — and never dumps raw tool output. The exact twin of the iOS
 * `ToolDisplayGoldenTests`, sharing the same fixture.
 */
class ToolDisplayGoldenTest {
    private val messages: List<MessageWithParts> by lazy {
        val body = javaClass.classLoader!!
            .getResourceAsStream("session-sqlite2orm.json")!!
            .bufferedReader().use { it.readText() }
        MessageParsing.parseMessageList(Json { ignoreUnknownKeys = true }, body)
    }

    private fun tool(name: String): PartContent.Tool =
        messages.flatMap { it.parts }.mapNotNull { it.content as? PartContent.Tool }.first { it.tool == name }

    @Test fun fixtureDecodes() {
        assertTrue(messages.isNotEmpty())
        val parts = messages.flatMap { it.parts }.mapNotNull { it.content }
        assertTrue(parts.any { it is PartContent.Tool })
        assertTrue(parts.any { it is PartContent.Patch })
        assertTrue(parts.any { it is PartContent.FileRef })
    }

    @Test fun readShowsFilenameOnly_neverDumpsContent() {
        val (label, detail) = ToolDisplay.describe(tool("read"))
        assertEquals("Read", label)
        detail?.let {
            assertFalse("no raw output markup", it.contains("<"))
            assertFalse("single line", it.contains("\n"))
            assertFalse("filename only, like the web client", it.contains("/"))
        }
    }

    // The detail screen shows the tool's content, not OpenCode's LLM-facing
    // <path>/<type>/<content> envelope (which was showing up raw).
    @Test fun cleanOutputStripsXmlWrapper() {
        val t = PartContent.Tool(
            tool = "read", callID = "c", status = "completed",
            output = "<path>/x/y.cpp</path>\n<type>file</type>\n<content>\n745: code line\n746: more\n</content>",
        )
        val clean = ToolDisplay.cleanOutput(t)!!
        assertFalse(clean.contains("<path>"))
        assertFalse(clean.contains("<type>"))
        assertFalse(clean.contains("<content>"))
        assertTrue(clean.contains("745: code line"))
    }

    @Test fun cleanOutputPassesThroughPlain() {
        val t = PartContent.Tool(tool = "bash", callID = "c", status = "completed", output = "hello world")
        assertEquals("hello world", ToolDisplay.cleanOutput(t))
    }

    // read on a directory wraps the listing in <entries> instead of <content>.
    @Test fun cleanOutputStripsDirectoryEntries() {
        val t = PartContent.Tool(
            tool = "read", callID = "c", status = "completed",
            output = "<path>/mnt/x</path>\n<type>directory</type>\n<entries>\n.git/\nsrc/\nREADME.md\n</entries>",
        )
        val clean = ToolDisplay.cleanOutput(t)!!
        assertFalse(clean.contains("<path>"))
        assertFalse(clean.contains("<entries>"))
        assertFalse(clean.contains("<type>"))
        assertTrue(clean.contains("src/"))
        assertTrue(clean.contains("README.md"))
    }

    @Test fun editShowsFilenameAndDiffBadge() {
        val (label, detail) = ToolDisplay.describe(tool("edit"))
        assertEquals("Edit", label)
        // Real filediff in the fixture: additions 5, deletions 4 (U+2212 minus).
        assertEquals("ast_query.h  +5 −4", detail)
    }

    @Test fun shellShowsDescription() {
        val (label, detail) = ToolDisplay.describe(tool("bash"))
        assertEquals("Shell", label)
        assertEquals("Configure cmake build", detail)
    }

    @Test fun grepShowsPatternAndMatchCount() {
        val (label, detail) = ToolDisplay.describe(tool("grep"))
        assertEquals("Grep", label)
        assertEquals("BindParameter|bind.*param|:refId|:result  no matches", detail)
    }

    @Test fun todoShowsCompletedRatio() {
        val (label, detail) = ToolDisplay.describe(tool("todowrite"))
        assertEquals("To-dos", label)
        assertEquals("0/5", detail)
    }

    @Test fun questionLabel() {
        assertEquals("Questions", ToolDisplay.describe(tool("question")).first)
    }

    @Test fun patchSummary() {
        val patch = messages.flatMap { it.parts }.mapNotNull { it.content as? PartContent.Patch }.first()
        assertEquals("1 file", PatchDisplay.summary(patch))
    }

    @Test fun fileChipShowsNameAndLine() {
        val file = messages.flatMap { it.parts }.mapNotNull { it.content as? PartContent.FileRef }.first()
        assertEquals("codegen_tests_create_table.cpp:266", FileRefDisplay.chip(file))
    }

    @Test fun diffBadgeEdgeCases() {
        assertNull(ToolDisplay.diffBadge(null))
        assertNull(ToolDisplay.diffBadge(ToolMeta(additions = 0, deletions = 0)))
        assertEquals("+3 −0", ToolDisplay.diffBadge(ToolMeta(additions = 3, deletions = 0)))
    }

    @Test fun lineRangeSpanAndSingle() {
        assertEquals("10", FileRefDisplay.lineRange("file:///a.cpp?start=10&end=10"))
        assertEquals("10-20", FileRefDisplay.lineRange("file:///a.cpp?start=10&end=20"))
        assertNull(FileRefDisplay.lineRange("file:///a.cpp"))
    }

    @Test fun todoRatio() {
        assertEquals("1/2", ToolDisplay.todoRatio(ToolMeta(todoTotal = 2, todoCompleted = 1)))
        assertNull(ToolDisplay.todoRatio(null))
        assertNull(ToolDisplay.todoRatio(ToolMeta(todoTotal = 0, todoCompleted = 0)))
    }

    @Test fun labelForEveryTool() {
        val cases = mapOf(
            "read" to "Read", "list" to "List", "glob" to "Glob", "grep" to "Grep",
            "bash" to "Shell", "shell" to "Shell", "edit" to "Edit", "write" to "Write",
            "patch" to "Patch", "apply_patch" to "Patch", "webfetch" to "Webfetch",
            "websearch" to "Web Search", "task" to "Agent", "todowrite" to "To-dos",
            "todo" to "To-dos", "question" to "Questions",
        )
        for ((tool, label) in cases) assertEquals("label of $tool", label, ToolDisplay.label(tool))
        // Unknown tools fall through to the raw name.
        assertEquals("mystery", ToolDisplay.label("mystery"))
    }

    private fun tool(name: String, input: Map<String, String>) =
        PartContent.Tool(tool = name, callID = "c", status = "completed", input = input)

    @Test fun describeWebAndListAndGlobBranches() {
        assertEquals("http://x/y" , ToolDisplay.describe(tool("webfetch", mapOf("url" to "http://x/y"))).second)
        assertEquals("swift concurrency", ToolDisplay.describe(tool("websearch", mapOf("query" to "swift concurrency"))).second)
        assertEquals("*.kt", ToolDisplay.describe(tool("glob", mapOf("pattern" to "*.kt"))).second)
        // list → base() of the path (last path component).
        assertEquals("src", ToolDisplay.describe(tool("list", mapOf("path" to "/w/src"))).second)
    }
}
