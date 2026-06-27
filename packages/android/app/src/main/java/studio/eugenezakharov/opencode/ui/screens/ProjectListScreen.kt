package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    server: ServerConnection,
    onProjectClick: (Project) -> Unit,
    onSessionCreated: (Session) -> Unit,
    onDisconnect: () -> Unit,
) {
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var showOpenFolder by remember { mutableStateOf(false) }
    var folderPath by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching { server.projects() }
            .onSuccess { projects = it }
            .onFailure { error = it.message ?: "Failed to load projects" }
        loading = false
    }

    fun openFolder() {
        val directory = folderPath.trim()
        if (directory.isEmpty()) return
        creating = true
        createError = null
        scope.launch {
            runCatching { server.createSession(directory) }
                .onSuccess { session ->
                    creating = false
                    showOpenFolder = false
                    folderPath = ""
                    onSessionCreated(session)
                }
                .onFailure {
                    creating = false
                    createError = it.message ?: "Failed to open folder"
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                actions = {
                    IconButton(
                        onClick = { showOpenFolder = true },
                        modifier = Modifier.testTag("projects.openFolder"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Open folder")
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Filled.Close, contentDescription = "Disconnect")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                error != null -> CenteredMessage("Error", error!!)
                projects.isEmpty() -> EmptyProjects(onOpenFolder = { showOpenFolder = true })
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(projects, key = { it.id }) { project ->
                        ProjectRow(project, Modifier.clickable { onProjectClick(project) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showOpenFolder) {
        OpenFolderDialog(
            path = folderPath,
            onPathChange = { folderPath = it },
            creating = creating,
            error = createError,
            onCreate = { openFolder() },
            onDismiss = {
                if (!creating) {
                    showOpenFolder = false
                    createError = null
                }
            },
        )
    }
}

@Composable
private fun EmptyProjects(onOpenFolder: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CenteredMessage(
            "No projects yet",
            "Open a folder on the server to start a new session.",
        )
        Spacer(Modifier.padding(top = 8.dp))
        Button(
            onClick = onOpenFolder,
            modifier = Modifier.testTag("projects.openFolder.empty"),
        ) {
            Text("Open a folder")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenFolderDialog(
    path: String,
    onPathChange: (String) -> Unit,
    creating: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open folder") },
        text = {
            Column {
                Text(
                    "Enter the absolute path of a folder on the OpenCode server. A new session opens there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = onPathChange,
                    singleLine = true,
                    placeholder = { Text("/absolute/path/to/project") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("openFolder.path"),
                )
                if (error != null) {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (creating) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            } else {
                TextButton(
                    onClick = onCreate,
                    enabled = path.trim().isNotEmpty(),
                    modifier = Modifier.testTag("openFolder.create"),
                ) {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ProjectRow(project: Project, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            project.name ?: project.worktree.substringAfterLast('/').ifEmpty { "Unknown" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            shortenPath(project.worktree),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun shortenPath(path: String): String {
    val marker = "/Users/"
    val idx = path.indexOf(marker)
    if (idx < 0) return path
    val afterUsers = path.substring(idx + marker.length)
    val slash = afterUsers.indexOf('/')
    return if (slash >= 0) "~" + afterUsers.substring(slash) else path
}
