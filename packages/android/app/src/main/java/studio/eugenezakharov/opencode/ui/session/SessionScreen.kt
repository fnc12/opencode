package studio.eugenezakharov.opencode.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.ui.screens.CenteredMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    session: Session,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.title.ifEmpty { "Untitled" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { StreamStatusBadge(state.status) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null -> CenteredMessage("Error", state.error!!)
                state.messages.isEmpty() -> CenteredMessage("No Messages", "This session has no messages yet")
                else -> MessageList(state)
            }
        }
    }
}

/**
 * The conversation list as a RecyclerView wrapped in [AndroidView] (per the
 * product decision — NOT a Compose LazyColumn). `update` re-submits the message
 * list; the adapter issues minimal notifications so a streaming delta rebinds a
 * single row. Auto-scrolls to the bottom while pinned there.
 */
@Composable
private fun MessageList(state: SessionUiState) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                adapter = MessageAdapter()
                clipToPadding = false
                setPadding(0, 8, 0, 8)
                tag = false // "was at bottom" flag updated on scroll
            }
        },
        update = { recycler ->
            val adapter = recycler.adapter as MessageAdapter
            val lm = recycler.layoutManager as LinearLayoutManager
            val atBottom = lm.findLastVisibleItemPosition() >= adapter.itemCount - 2 || adapter.itemCount == 0
            val changed = adapter.submit(state.messages)
            if (changed && atBottom && state.messages.isNotEmpty()) {
                recycler.post { recycler.scrollToPosition(state.messages.size - 1) }
            }
        },
        // Re-run `update` whenever the revision changes (text grew) or count changes.
        onReset = {},
    )
    // Touch revision so recomposition re-invokes update on every applied change.
    @Suppress("UNUSED_EXPRESSION")
    state.revision
}

@Composable
private fun StreamStatusBadge(status: SessionStore.StreamStatus) {
    when (status) {
        SessionStore.StreamStatus.IDLE -> {}
        SessionStore.StreamStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● Live", color = androidx.compose.ui.graphics.Color(0xFF4CD964), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
        }
        SessionStore.StreamStatus.CONNECTING,
        SessionStore.StreamStatus.RECONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (status == SessionStore.StreamStatus.CONNECTING) "Connecting" else "Reconnecting",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
