package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.FolderPrefs
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.FileEntry
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
    var showProviders by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current.applicationContext
    val folderPrefs = remember { FolderPrefs(context) }
    var folderPath by remember { mutableStateOf(folderPrefs.lastPath) }

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
                    folderPrefs.lastPath = directory
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
                        onClick = { showProviders = true },
                        modifier = Modifier.testTag("projects.providers"),
                    ) {
                        Text("🔑")
                    }
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
                else -> {
                    val primary = MaterialTheme.colorScheme.onSurface.toArgb()
                    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            androidx.recyclerview.widget.RecyclerView(ctx).apply {
                                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                                adapter = ProjectListAdapter(primary, secondary, onClick = onProjectClick)
                            }
                        },
                        update = { rv -> (rv.adapter as ProjectListAdapter).submit(projects) },
                    )
                }
            }
        }
    }

    if (showOpenFolder) {
        OpenFolderBrowser(
            server = server,
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

    if (showProviders) {
        ProvidersScreen(server = server, onDismiss = { showProviders = false })
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

/**
 * A folder browser for the server's filesystem: navigate into subfolders (or type
 * a path), then "Open here" to start a new session in the current folder. This is
 * how you begin work on a fresh server. Mirrors the iOS `OpenFolderSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenFolderBrowser(
    server: ServerConnection,
    path: String,
    onPathChange: (String) -> Unit,
    creating: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(newPath: String) {
        val target = newPath.ifEmpty { "/" }
        listLoading = true
        loadError = null
        scope.launch {
            runCatching { server.listDirectory(target) }
                .onSuccess { result ->
                    onPathChange(target)
                    entries = result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
                .onFailure { loadError = "Can't open $target" }
            listLoading = false
        }
    }

    LaunchedEffect(Unit) { load(path.ifEmpty { "/" }) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !creating) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                title = { Text("Open folder") },
                actions = {
                    if (creating) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp).size(24.dp))
                    } else {
                        TextButton(
                            onClick = onCreate,
                            enabled = path.trim().isNotEmpty(),
                            modifier = Modifier.testTag("openFolder.create"),
                        ) {
                            Text("Open here")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = path,
                    onValueChange = onPathChange,
                    singleLine = true,
                    placeholder = { Text("/path/on/server") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { load(path) },
                    ),
                    modifier = Modifier.weight(1f).testTag("openFolder.path"),
                )
                if (path != "/" && path.isNotEmpty()) {
                    IconButton(
                        onClick = { load(parentOf(path)) },
                        modifier = Modifier.testTag("openFolder.up"),
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Parent folder")
                    }
                }
            }
            HorizontalDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    listLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    loadError != null -> Text(
                        loadError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> {
                        val folders = entries.filter { it.isDirectory }
                        if (folders.isEmpty()) {
                            Text(
                                "No subfolders here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(folders, key = { it.absolute }) { entry ->
                                    FolderRow(
                                        entry,
                                        Modifier
                                            .clickable { load(entry.absolute) }
                                            .testTag("dir.${entry.name}"),
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderRow(entry: FileEntry, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "📁",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.padding(start = 8.dp))
        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Parent directory of an absolute path; "/" stays "/". Mirrors the iOS `parent(of:)`. */
private fun parentOf(p: String): String {
    val trimmed = if (p.length > 1 && p.endsWith('/')) p.dropLast(1) else p
    val slash = trimmed.lastIndexOf('/')
    return if (slash <= 0) "/" else trimmed.substring(0, slash)
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
