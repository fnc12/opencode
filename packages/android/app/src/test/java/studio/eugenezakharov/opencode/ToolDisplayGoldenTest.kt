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
}
