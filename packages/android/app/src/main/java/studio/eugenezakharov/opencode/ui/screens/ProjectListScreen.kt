package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    server: ServerConnection,
    onProjectClick: (Project) -> Unit,
    onDisconnect: () -> Unit,
) {
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching { server.projects() }
            .onSuccess { projects = it }
            .onFailure { error = it.message ?: "Failed to load projects" }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                actions = {
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
                projects.isEmpty() -> CenteredMessage("No Projects", "No projects found on this server")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(projects, key = { it.id }) { project ->
                        ProjectRow(project, Modifier.clickable { onProjectClick(project) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
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
