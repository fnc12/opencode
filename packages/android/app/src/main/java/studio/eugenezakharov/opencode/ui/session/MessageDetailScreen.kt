package studio.eugenezakharov.opencode.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent

private sealed interface DetailBlock {
    data class Thinking(val text: String) : DetailBlock
    data class Body(val text: String) : DetailBlock
    data class ToolBlock(val title: String, val output: String?) : DetailBlock
    data class Note(val text: String) : DetailBlock
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
                                SelectionContainer {
                                    Text(block.text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            is DetailBlock.Body -> SelectionContainer {
                                Text(block.text, style = MaterialTheme.typography.bodyMedium)
                            }

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
                                if (!block.output.isNullOrEmpty()) {
                                    Spacer(Modifier.size(4.dp))
                                    SelectionContainer {
                                        Text(block.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
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

private fun detailBlocks(message: MessageWithParts): List<DetailBlock> {
    val out = mutableListOf<DetailBlock>()
    for (part in message.parts) {
        if (!part.isVisible) continue
        when (val c = part.content) {
            is PartContent.Text -> if (c.text.isNotBlank()) out.add(DetailBlock.Body(c.text))
            is PartContent.Reasoning -> if (c.text.isNotBlank()) out.add(DetailBlock.Thinking(c.text))
            is PartContent.Tool -> {
                val (label, detail) = ToolDisplay.describe(c)
                val title = if (!detail.isNullOrEmpty()) "$label  $detail" else label
                out.add(DetailBlock.ToolBlock(title, c.output))
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
        is DetailBlock.ToolBlock -> if (block.output.isNullOrEmpty()) "→ ${block.title}" else "→ ${block.title}\n\n${block.output}"
        is DetailBlock.Note -> block.text
    }
}
