package studio.eugenezakharov.opencode.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
                actions = {
                    // Stop button (#29): only while a reply is generating. Mirrors iOS.
                    if (state.isBusy) {
                        TextButton(
                            onClick = { viewModel.abort() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.testTag("session.stop"),
                        ) {
                            // A filled square glyph stands in for a "stop" icon
                            // (material-icons-core has no Stop symbol).
                            Text("■", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                    StreamStatusBadge(state.status)
                },
            )
        },
        bottomBar = {
            // The composer is visible even on an empty session, so a conversation
            // can be started. Hidden only while history is still loading or failed.
            if (!state.loading && state.error == null) {
                Column {
                    // Permission docks sit above the composer; the agent is blocked
                    // until each is answered (#27).
                    state.pendingPermissions.forEach { request ->
                        PermissionDock(
                            request = request,
                            onReply = { reply -> viewModel.replyPermission(request, reply) },
                        )
                    }
                    // Question docks also sit above the composer; the agent is blocked
                    // until each is answered or skipped (#28).
                    state.pendingQuestions.forEach { request ->
                        QuestionDock(
                            request = request,
                            onReply = { answers -> viewModel.replyQuestion(request, answers) },
                            onReject = { viewModel.rejectQuestion(request) },
                        )
                    }
                    Composer(state = state, viewModel = viewModel)
                }
            }
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
 * A pending permission request shown above the composer (#27). The agent is
 * blocked until the user answers — Allow (once), Always, or Reject — which is
 * the core reason to control a session from the phone. Mirrors iOS
 * `PermissionDock`.
 */
@Composable
private fun PermissionDock(
    request: studio.eugenezakharov.opencode.api.models.PermissionRequest,
    onReply: (String) -> Unit,
) {
    val warning = androidx.compose.ui.graphics.Color(0xFFE8951F)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = warning.copy(alpha = 0.35f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .background(
                color = warning.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            "⚠ Permission needed",
            color = warning,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            request.summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("permission.summary"),
        )
        Spacer(Modifier.size(10.dp))
        Row {
            OutlinedButton(
                onClick = { onReply("reject") },
                modifier = Modifier.weight(1f).testTag("permission.reject"),
            ) { Text("Reject") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = { onReply("always") },
                modifier = Modifier.weight(1f).testTag("permission.always"),
            ) { Text("Always") }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { onReply("once") },
                modifier = Modifier.weight(1f).testTag("permission.allow"),
            ) { Text("Allow") }
        }
    }
}

/**
 * A pending agent question shown above the composer (#28). Each question is
 * single-select (radio) or multi-select (toggle when `multiple==true`); the
 * reply is one array of selected labels per question. The agent is blocked until
 * the user submits answers or skips. Blue/info styling distinguishes it from the
 * orange permission dock. Mirrors iOS `QuestionDock`.
 */
@Composable
private fun QuestionDock(
    request: studio.eugenezakharov.opencode.api.models.QuestionRequest,
    onReply: (List<List<String>>) -> Unit,
    onReject: () -> Unit,
) {
    val info = androidx.compose.ui.graphics.Color(0xFF2D7FF9)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    // Selected labels per question, keyed by the question's stable key.
    val selections = remember(request.id) { mutableStateMapOf<String, Set<String>>() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(width = 1.dp, color = info.copy(alpha = 0.35f), shape = shape)
            .background(color = info.copy(alpha = 0.10f), shape = shape)
            .padding(12.dp),
    ) {
        Text(
            "? Question",
            color = info,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(8.dp))

        request.questions.forEach { question ->
            if (question.header.isNotEmpty()) {
                Text(
                    question.header,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(2.dp))
            }
            Text(question.question, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(6.dp))

            question.options.forEach { option ->
                val selected = selections[question.key]?.contains(option.label) == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val current = selections[question.key] ?: emptySet()
                            selections[question.key] = if (question.allowsMultiple) {
                                if (option.label in current) current - option.label else current + option.label
                            } else {
                                setOf(option.label) // radio
                            }
                        }
                        .padding(vertical = 6.dp)
                        .testTag(option.label),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected) "●" else "○",
                        color = if (selected) info else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        if (option.description.isNotEmpty()) {
                            Text(
                                option.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.testTag("question.reject"),
            ) { Text("Skip") }
            Spacer(Modifier.weight(1f))
            val canSubmit = request.questions.all { !(selections[it.key].isNullOrEmpty()) }
            Button(
                onClick = { onReply(request.questions.map { (selections[it.key] ?: emptySet()).toList() }) },
                enabled = canSubmit,
                modifier = Modifier.testTag("question.submit"),
            ) { Text("Submit") }
        }
    }
}

/**
 * Bottom composer: model picker, text input (the keyboard's mic key covers
 * dictation), and a send button. Mirrors iOS `ComposerView`. Sending clears the
 * input optimistically; the reply streams back over the SSE already wired.
 */
@Composable
private fun Composer(state: SessionUiState, viewModel: SessionViewModel) {
    var text by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            state.sendError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.size(4.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                FilledTonalButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.testTag("composer.model"),
                ) {
                    Text(viewModel.modelLabel(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message") },
                    enabled = !state.sending,
                    maxLines = 5,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("composer.field"),
                )
                Spacer(Modifier.width(8.dp))
                val canSend = text.isNotBlank() && !state.sending && state.modelID.isNotEmpty()
                IconButton(
                    onClick = { viewModel.send(text, onCleared = { text = "" }, restore = { text = it }) },
                    enabled = canSend,
                    modifier = Modifier.testTag("composer.send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            state = state,
            onSelect = { p, m -> viewModel.selectModel(p, m); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    state: SessionUiState,
    onSelect: (providerID: String, modelID: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Model") },
        text = {
            LazyColumn {
                state.providers.forEach { provider ->
                    item(key = "p_${provider.id}") {
                        Text(
                            provider.name ?: provider.id,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(provider.models, key = { "${provider.id}/${it.id}" }) { model ->
                        val selected = provider.id == state.providerID && model.id == state.modelID
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(provider.id, model.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                model.displayName,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (selected) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
    )
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
        onReset = {},
    )
    @Suppress("UNUSED_EXPRESSION")
    state.revision
}

@Composable
private fun StreamStatusBadge(status: SessionStore.StreamStatus) {
    when (status) {
        SessionStore.StreamStatus.IDLE -> {}
        SessionStore.StreamStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "● Live",
                color = androidx.compose.ui.graphics.Color(0xFF4CD964),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("session.live"),
            )
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
