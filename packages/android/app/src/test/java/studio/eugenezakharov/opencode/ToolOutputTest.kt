package studio.eugenezakharov.opencode

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.ToolMeta
import studio.eugenezakharov.opencode.ui.session.diffAnnotated

/**
 * The detail screen renders edit/write diffs colored (additions green, deletions
 * red, hunk headers highlighted) and decodes metadata.diff + todos — parity with
 * the iOS client.
 */
class ToolOutputTest {
    private val add = Color(0xFF1F9550)
    private val del = Color(0xFFD1453B)
    private val hunk = Color(0xFF3366FF)
    private val dim = Color(0xFF888888)

    @Test fun diffColorsAddDeleteHunk() {
        val s = diffAnnotated("@@ -1 +1 @@\n-old line\n+new line", add, del, hunk, dim)
        fun colorAt(sub: String): Color {
            val i = s.text.indexOf(sub)
            return s.spanStyles.first { i >= it.start && i < it.end }.item.color
        }
        assertEquals(add, colorAt("+new line"))
        assertEquals(del, colorAt("-old line"))
        assertEquals(hunk, colorAt("@@"))
    }

    @Test fun fileHeadersNotColoredAsChanges() {
        val s = diffAnnotated("--- a/x\n+++ b/x\n+added", add, del, hunk, dim)
        val i = s.text.indexOf("+++ b/x")
        assertEquals(dim, s.spanStyles.first { i >= it.start && i < it.end }.item.color)
    }

    @Test fun toolMetaDecodesDiffAndTodos() {
        val json = Json.parseToJsonElement(
            """{"diff":"@@ -1 +1 @@\n-a\n+b","todos":[{"content":"do it","status":"completed"},{"content":"next","status":"pending"}]}""",
        )
        val meta = ToolMeta.from(json)!!
        assertEquals("@@ -1 +1 @@\n-a\n+b", meta.diff)
        assertEquals(2, meta.todos.size)
        assertEquals("do it", meta.todos[0].content)
        assertTrue(meta.todos[0].status == "completed")
        assertEquals(1, meta.todoCompleted)
    }
}
