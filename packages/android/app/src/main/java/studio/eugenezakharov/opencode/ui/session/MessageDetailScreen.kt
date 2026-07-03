package studio.eugenezakharov.opencode.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent

/** One message on its own screen (Slack-style): full text + the complete thinking
 *  + each tool's output, as selectable/copyable text. Mirrors iOS MessageDetailView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(message: MessageWithParts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = fullText(message)
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
                                cm.setPrimaryClip(ClipData.newPlainText("message", text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                        },
                    )
                },
            ) { pad ->
                SelectionContainer {
                    Column(
                        Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        Text(text)
                    }
                }
            }
        }
    }
}

private fun fullText(message: MessageWithParts): String {
    val out = mutableListOf<String>()
    for (part in message.parts) {
        if (!part.isVisible) continue
        when (val c = part.content) {
            is PartContent.Text -> if (c.text.isNotBlank()) out.add(c.text)
            is PartContent.Reasoning -> if (c.text.isNotBlank()) out.add("💭 Thinking\n\n${c.text}")
            is PartContent.Tool -> {
                val (label, detail) = ToolDisplay.describe(c)
                var block = "→ $label"
                if (!detail.isNullOrEmpty()) block += "  $detail"
                if (!c.output.isNullOrEmpty()) block += "\n\n${c.output}"
                out.add(block)
            }
            is PartContent.Patch -> out.add("⌥ Patch — ${c.files.size} file(s)")
            is PartContent.FileRef -> out.add("📎 ${c.filename ?: c.url ?: ""}")
            else -> {}
        }
    }
    return out.joinToString("\n\n")
}
