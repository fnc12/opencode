package studio.eugenezakharov.opencode.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Session

/** A pocket terminal: type a shell command, it runs on the server (`POST /session/:id/shell`)
 *  and the output prints below. Mirrors iOS `ShellView`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(server: ServerConnection, session: Session, onDismiss: () -> Unit) {
    var command by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        command = ""
        running = true
        scope.launch {
            val out = runCatching { server.runShell(session.directory, session.id, cmd) }
                .getOrElse { "error: ${it.message}" }
            log = log + (cmd to out)
            running = false
        }
    }

    LaunchedEffect(log.size, running) {
        val target = log.size + if (running) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Shell") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        state = listState,
                    ) {
                        if (log.isEmpty() && !running) {
                            item {
                                Text(
                                    "Run a shell command in ${session.directory}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                        items(log) { (cmd, out) ->
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "$ $cmd",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF1F9550),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            SelectionContainer {
                                Text(
                                    out,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        if (running) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(6.dp))
                                    Text("running…", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            placeholder = { Text("command", fontFamily = FontFamily.Monospace) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            enabled = !running,
                            modifier = Modifier.weight(1f).testTag("shell.field"),
                        )
                        IconButton(onClick = { run() }, modifier = Modifier.testTag("shell.run")) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run")
                        }
                    }
                }
            }
        }
    }
}
