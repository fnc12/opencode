package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.Session

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    server: ServerConnection,
    project: Project,
    onSessionClick: (Session) -> Unit,
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Session?>(null) }
    var renameFor by remember { mutableStateOf<Session?>(null) }
    var renameText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(project.id) {
        loading = true
        error = null
        runCatching { server.sessions(project.worktree) }
            .onSuccess { sessions = it.sortedByDescending { s -> s.time.updated } }
            .onFailure { error = it.message ?: "Failed to load sessions" }
        loading = false
    }

    fun newSession() {
        if (creating) return
        creating = true
        scope.launch {
            runCatching { server.createSession(project.worktree) }
                .onSuccess { creating = false; onSessionClick(it) }
                .onFailure { creating = false; error = it.message ?: "Failed to create session" }
        }
    }

    fun deleteSession(session: Session) {
        scope.launch {
            runCatching { server.deleteSession(project.worktree, session.id) }
                .onSuccess { sessions = sessions.filter { it.id != session.id } }
                .onFailure { error = it.message ?: "Failed to delete session" }
        }
    }

    fun commitRename(target: Session) {
        val newTitle = renameText.trim()
        renameFor = null
        if (newTitle.isEmpty() || newTitle == target.title) return
        scope.launch {
            runCatching {
                server.renameSession(project.worktree, target.id, newTitle)
                server.sessions(project.worktree)
            }
                .onSuccess { s -> sessions = s.sortedByDescending { it.time.updated } }
                .onFailure { error = it.message ?: "Failed to rename session" }
        }
    }

    val title = project.name ?: project.worktree.substringAfterLast('/').ifEmpty { "Sessions" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (creating) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    } else {
                        IconButton(
                            onClick = { newSession() },
                            modifier = Modifier.testTag("sessions.new"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "New session")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                error != null -> CenteredMessage("Error", error!!)
                sessions.isEmpty() -> EmptySessions(creating = creating, onNewSession = { newSession() })
                else -> {
                    val primary = MaterialTheme.colorScheme.onSurface.toArgb()
                    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                    val addColor = android.graphics.Color.parseColor("#1F9550")
                    val delColor = MaterialTheme.colorScheme.error.toArgb()
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            RecyclerView(ctx).apply {
                                layoutManager = LinearLayoutManager(ctx)
                                adapter = SessionListAdapter(
                                    primary, secondary, addColor, delColor,
                                    onClick = onSessionClick,
                                    onRename = { session -> renameText = session.title; renameFor = session },
                                    onDelete = { session -> deleteSession(session) },
                                )
                            }
                        },
                        update = { rv -> (rv.adapter as SessionListAdapter).submit(sessions) },
                    )
                }
            }
        }
    }

    renameFor?.let { target ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.testTag("session.rename.field"),
                )
            },
            confirmButton = { TextButton(onClick = { commitRename(target) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptySessions(creating: Boolean, onNewSession: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CenteredMessage("No sessions yet", "Start a new session in this project.")
        Spacer(Modifier.padding(top = 8.dp))
        Button(
            onClick = onNewSession,
            enabled = !creating,
            modifier = Modifier.testTag("sessions.new.empty"),
        ) {
            Text("New session")
        }
    }
}

@Composable
private fun SessionRow(session: Session, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            session.title.ifEmpty { "Untitled" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
        )
        Spacer(Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            session.summary?.let { summary ->
                if (summary.additions > 0) {
                    Text("+${summary.additions} ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (summary.deletions > 0) {
                    Text("-${summary.deletions} ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (summary.files > 0) {
                    Text("${summary.files} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                relativeTime(session.time.updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Coarse relative-time label (ms epoch). */
fun relativeTime(epochMillis: Double): String {
    val now = System.currentTimeMillis()
    val diff = (now - epochMillis.toLong()).coerceAtLeast(0)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}
