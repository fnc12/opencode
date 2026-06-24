package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.Session

@OptIn(ExperimentalMaterial3Api::class)
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

    LaunchedEffect(project.id) {
        loading = true
        error = null
        runCatching { server.sessions(project.worktree) }
            .onSuccess { sessions = it.sortedByDescending { s -> s.time.updated } }
            .onFailure { error = it.message ?: "Failed to load sessions" }
        loading = false
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
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                error != null -> CenteredMessage("Error", error!!)
                sessions.isEmpty() -> CenteredMessage("No Sessions", "No sessions in this project")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(session, Modifier.clickable { onSessionClick(session) })
                        HorizontalDivider()
                    }
                }
            }
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
private fun relativeTime(epochMillis: Double): String {
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
