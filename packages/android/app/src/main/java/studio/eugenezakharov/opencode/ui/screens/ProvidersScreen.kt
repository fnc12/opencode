package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import studio.eugenezakharov.opencode.api.ServerConnection

/** Manage provider API keys. Mirrors iOS `ProvidersView`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(server: ServerConnection, onDismiss: () -> Unit) {
    var apiProviders by remember { mutableStateOf<List<String>>(emptyList()) }
    var configured by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var keyEntryFor by remember { mutableStateOf<String?>(null) }
    var keyText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val methods = runCatching { server.providerAuthMethods() }.getOrDefault(emptyMap())
        apiProviders = methods.filter { entry -> entry.value.any { it.type == "api" } }.keys.sorted()
        configured = runCatching { server.providers() }.getOrDefault(emptyList()).map { it.id }.toSet()
        loading = false
    }

    fun displayName(id: String) =
        id.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Providers") },
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
                    item {
                        Text(
                            "API KEY PROVIDERS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(apiProviders, key = { it }) { id ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { keyEntryFor = id; keyText = "" }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(displayName(id), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            if (id in configured) Text("✓", color = Color(0xFF1F9550))
                        }
                        HorizontalDivider()
                    }
                    item {
                        Text(
                            "Tap a provider to paste its API key. A ✓ means it's configured. " +
                                "OAuth logins (ChatGPT, Copilot) need a browser — set those up on desktop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    keyEntryFor?.let { id ->
        AlertDialog(
            onDismissRequest = { keyEntryFor = null },
            title = { Text("API Key") },
            text = {
                Column {
                    Text("Enter the API key for ${displayName(id)}.")
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        singleLine = true,
                        placeholder = { Text("sk-…") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = keyText.trim()
                    keyEntryFor = null
                    if (k.isNotEmpty()) scope.launch {
                        runCatching { server.setProviderKey(id, k) }
                            .onSuccess { configured = configured + id }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { keyEntryFor = null }) { Text("Cancel") } },
        )
    }
}
