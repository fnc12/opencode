package studio.eugenezakharov.opencode.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.FileEntry

/** Browse the server's filesystem and pick a file to attach as context. Mirrors iOS `FilePickerSheet`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerDialog(
    server: ServerConnection,
    startPath: String,
    onPick: (FileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(target: String) {
        val t = target.ifEmpty { "/" }
        loading = true
        scope.launch {
            entries = runCatching { server.listDirectory(t) }.getOrDefault(emptyList())
            path = t
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(startPath) }

    val sorted = entries.sortedWith(
        compareByDescending<FileEntry> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(path.substringAfterLast('/').ifEmpty { "Files" }, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        },
                    )
                },
            ) { pad ->
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(pad).padding(24.dp))
                    return@Scaffold
                }
                LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                    if (path != "/") {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { load(parentDir(path)) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { Text("↰  ..", style = MaterialTheme.typography.bodyLarge) }
                            HorizontalDivider()
                        }
                    }
                    items(sorted, key = { it.absolute }) { entry ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (entry.isDirectory) load(entry.absolute) else { onPick(entry); onDismiss() } }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (entry.isDirectory) "📁" else "📄")
                            Spacer(Modifier.size(10.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

internal fun parentDir(p: String): String {
    val trimmed = if (p.length > 1 && p.endsWith('/')) p.dropLast(1) else p
    val slash = trimmed.lastIndexOf('/')
    return if (slash <= 0) "/" else trimmed.substring(0, slash)
}
