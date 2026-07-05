package studio.eugenezakharov.opencode.ui.session

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.api.models.TodoItem

/**
 * Per-tool rendering of a tool result on the detail screen, mirroring the web
 * client (and the iOS twin): edit/write show a colored diff, todowrite a
 * checklist, everything else the clean (envelope-stripped) output as monospaced
 * text.
 */
@Composable
fun ToolOutput(tool: PartContent.Tool) {
    val kind = tool.tool.lowercase()
    val diff = tool.metadata?.diff
    val todos = tool.metadata?.todos ?: emptyList()
    when {
        kind in EDIT_TOOLS && !diff.isNullOrEmpty() -> DiffText(diff)
        kind == "read" && tool.output?.contains("<type>file") == true -> {
            val content = ToolDisplay.cleanOutput(tool)
            if (!content.isNullOrEmpty()) CodeText(content, filePath(tool))
        }
        kind in TODO_TOOLS && todos.isNotEmpty() -> TodoChecklist(todos)
        else -> {
            val out = ToolDisplay.cleanOutput(tool)
            if (!out.isNullOrEmpty()) {
                SelectionContainer {
                    Text(out, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val EDIT_TOOLS = setOf("edit", "write", "patch", "apply_patch")
private val TODO_TOOLS = setOf("todowrite", "todo")

private fun filePath(tool: PartContent.Tool): String? = tool.input["filePath"]

/** Syntax-highlighted read content with a dimmed line-number gutter. */
@Composable
fun CodeText(content: String, filename: String?) {
    val dark = isSystemInDarkTheme()
    val gutter = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(content, filename, dark) {
        CodeHighlighter.readContent(content, filename, dark, gutter)
    }
    SelectionContainer {
        Text(annotated, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** A unified diff colored like the web's ContentDiff: additions green, deletions
 *  red, hunk headers blue, file headers dimmed. Exposed for tests. */
fun diffAnnotated(
    diff: String,
    add: Color, del: Color, hunk: Color, dim: Color,
): AnnotatedString = buildAnnotatedString {
    diff.split("\n").forEachIndexed { i, line ->
        if (i > 0) append("\n")
        val color = when {
            line.startsWith("+++") || line.startsWith("---") -> dim
            line.startsWith("@@") -> hunk
            line.startsWith("+") -> add
            line.startsWith("-") -> del
            else -> dim
        }
        withStyle(SpanStyle(color = color)) { append(line) }
    }
}

@Composable
fun DiffText(diff: String) {
    val annotated = diffAnnotated(
        diff,
        add = Color(0xFF1F9550),
        del = Color(0xFFD1453B),
        hunk = MaterialTheme.colorScheme.primary,
        dim = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Text(annotated, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TodoChecklist(todos: List<TodoItem>) {
    Column {
        todos.forEach { todo ->
            val done = todo.status == "completed"
            val marker = when (todo.status) {
                "completed" -> "☑"
                "in_progress" -> "◐"
                else -> "☐"
            }
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    "$marker ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) Color(0xFF1F9550) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    todo.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
            }
            Spacer(Modifier.size(2.dp))
        }
    }
}
