package studio.eugenezakharov.opencode.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.TextView
import android.widget.Toast
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent

private sealed interface DetailBlock {
    data class Thinking(val text: String) : DetailBlock
    data class Body(val text: String) : DetailBlock
    data class ToolBlock(val title: String, val tool: PartContent.Tool) : DetailBlock
    data class Note(val text: String) : DetailBlock
    /** A GFM table lifted out of the body; rows[0] is the header. */
    data class TableBlock(val rows: List<List<String>>) : DetailBlock
}

/** One message on its own screen (Slack-style): the thinking set apart from the
 *  answer text and from each tool's output; every block character-selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(message: MessageWithParts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = detailBlocks(message)
    val title = if (message.info is MessageInfo.User) "You" else "Assistant"

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        },
                        actions = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("message", plainText(blocks)))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                        },
                    )
                },
            ) { pad ->
                Column(
                    Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    blocks.forEach { block ->
                        when (block) {
                            is DetailBlock.Thinking -> Column(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            ) {
                                Text(
                                    "💭 Thinking",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(4.dp))
                                MarkdownText(block.text, MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            is DetailBlock.Body -> MarkdownText(block.text, MaterialTheme.colorScheme.onSurface)

                            is DetailBlock.TableBlock -> TableGrid(block.rows)

                            is DetailBlock.ToolBlock -> Column(
                                Modifier.fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(12.dp),
                            ) {
                                Text(
                                    block.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(4.dp))
                                ToolOutput(block.tool)
                            }

                            is DetailBlock.Note -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** Renders a text block with the SAME Markdown renderer as the message list (a
 *  native selectable TextView) — the detail screen previously showed raw text
 *  with no formatting. */
@Composable
private fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val argb = color.toArgb()
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 15f
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.1f)
            }
        },
        update = { tv ->
            tv.setTextColor(argb)
            tv.text = MarkdownRenderer.render(text, 15, android.graphics.Color.TRANSPARENT)
        },
    )
}

/** A real grid for a GFM table: header row (bold), a rule, then data rows; the
 *  whole thing scrolls horizontally so wide tables stay aligned instead of
 *  wrapping. Cells are laid out column-major so rows line up. */
@Composable
private fun TableGrid(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val ncol = rows.maxOf { it.size }
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        for (c in 0 until ncol) {
            Column {
                rows.forEachIndexed { r, row ->
                    Text(
                        stripInline(row.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                        softWrap = false,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    if (r == 0) HorizontalDivider()
                }
            }
            if (c < ncol - 1) VerticalDivider()
        }
    }
}

/** Strips the inline markers we don't render inside grid cells (`**`, `*`, `_`, `` ` ``). */
private fun stripInline(s: String): String =
    s.replace("**", "").replace("`", "").replace("*", "").replace("_", "").trim()

private fun isTableRow(line: String) = line.contains('|') && line.isNotBlank()

private fun isSeparatorRow(line: String): Boolean {
    val cells = splitCells(line)
    return cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' } }
}

private fun splitCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split("|").map { it.trim() }
}

/** Splits a text part into Body runs and GFM TableBlocks (mirrors the iOS split). */
private fun splitBody(text: String): List<DetailBlock> {
    val res = mutableListOf<DetailBlock>()
    val lines = text.split("\n")
    val buf = StringBuilder()
    fun flush() {
        val t = buf.toString().trim()
        if (t.isNotEmpty()) res.add(DetailBlock.Body(t))
        buf.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        if (i + 1 < lines.size && isTableRow(lines[i]) && isSeparatorRow(lines[i + 1])) {
            flush()
            val rows = mutableListOf<List<String>>()
            var j = i
            while (j < lines.size && isTableRow(lines[j])) {
                if (!isSeparatorRow(lines[j])) rows.add(splitCells(lines[j]))
                j++
            }
            res.add(DetailBlock.TableBlock(rows))
            i = j
        } else {
            buf.append(lines[i]).append("\n")
            i++
        }
    }
    flush()
    return res
}

private fun detailBlocks(message: MessageWithParts): List<DetailBlock> {
    val out = mutableListOf<DetailBlock>()
    for (part in message.parts) {
        if (!part.isVisible) continue
        when (val c = part.content) {
            is PartContent.Text -> if (c.text.isNotBlank()) out.addAll(splitBody(c.text))
            is PartContent.Reasoning -> if (c.text.isNotBlank()) out.add(DetailBlock.Thinking(c.text))
            is PartContent.Tool -> {
                val (label, detail) = ToolDisplay.describe(c)
                val title = if (!detail.isNullOrEmpty()) "$label  $detail" else label
                out.add(DetailBlock.ToolBlock(title, c))
            }
            is PartContent.Patch -> out.add(DetailBlock.Note("⌥ Patch — ${c.files.size} file(s)"))
            is PartContent.FileRef -> out.add(DetailBlock.Note("📎 ${c.filename ?: c.url ?: ""}"))
            else -> {}
        }
    }
    return out
}

private fun plainText(blocks: List<DetailBlock>): String = blocks.joinToString("\n\n") { block ->
    when (block) {
        is DetailBlock.Thinking -> "💭 Thinking\n\n${block.text}"
        is DetailBlock.Body -> block.text
        is DetailBlock.ToolBlock -> {
            val isEdit = block.tool.tool.lowercase() in setOf("edit", "write", "patch", "apply_patch")
            val body = (if (isEdit) block.tool.metadata?.diff else null) ?: ToolDisplay.cleanOutput(block.tool)
            if (body.isNullOrEmpty()) "→ ${block.title}" else "→ ${block.title}\n\n$body"
        }
        is DetailBlock.Note -> block.text
        is DetailBlock.TableBlock -> block.rows.joinToString("\n") { it.joinToString(" | ") }
    }
}
