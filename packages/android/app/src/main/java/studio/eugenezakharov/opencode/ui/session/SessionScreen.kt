package studio.eugenezakharov.opencode.ui.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import studio.eugenezakharov.opencode.api.models.PromptAttachment
import studio.eugenezakharov.opencode.api.models.TodoItem
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import androidx.compose.material.icons.Icons
import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.eugenezakharov.opencode.api.models.SessionFileDiff
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
    var showDiff by remember { mutableStateOf(false) }
    var showShell by remember { mutableStateOf(false) }
    var showTodos by remember { mutableStateOf(false) }
    var detailMessage by remember { mutableStateOf<studio.eugenezakharov.opencode.api.models.MessageWithParts?>(null) }
    var showShareMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                    IconButton(
                        onClick = { showShell = true },
                        modifier = Modifier.testTag("session.shell"),
                    ) {
                        Text(">_", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(
                        onClick = { showDiff = true },
                        modifier = Modifier.testTag("session.diff"),
                    ) {
                        Text("±", style = MaterialTheme.typography.titleMedium)
                    }
                    Box {
                        IconButton(
                            onClick = { showShareMenu = true },
                            modifier = Modifier.testTag("session.share"),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Share link") },
                                onClick = {
                                    showShareMenu = false
                                    viewModel.share { url ->
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share session"))
                                    }
                                },
                            )
                            if (state.shareUrl != null) {
                                DropdownMenuItem(
                                    text = { Text("Stop sharing") },
                                    onClick = { showShareMenu = false; viewModel.unshare() },
                                )
                            }
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
                    if (state.todos.isNotEmpty()) {
                        TodoPill(state.todos) { showTodos = true }
                    }
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
                else -> MessageList(
                    state,
                    onRevert = { viewModel.revert(it) },
                    onSelect = { detailMessage = it },
                )
            }
        }
    }

    if (showDiff) {
        Dialog(
            onDismissRequest = { showDiff = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            DiffScreen(load = { viewModel.loadDiff() }, onClose = { showDiff = false })
        }
    }

    if (showTodos) {
        TodoDialog(state.todos, onDismiss = { showTodos = false })
    }

    detailMessage?.let { msg ->
        MessageDetailScreen(msg, onDismiss = { detailMessage = null })
    }

    if (showShell) {
        ShellScreen(server = viewModel.server, session = session, onDismiss = { showShell = false })
    }
}

/** A session's changes: a list of files with ± counts; tap a file for its full
 *  colored diff on its own screen (don't inline every diff on a phone). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffScreen(
    load: suspend () -> List<SessionFileDiff>,
    onClose: () -> Unit,
) {
    var diffs by remember { mutableStateOf<List<SessionFileDiff>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SessionFileDiff?>(null) }
    LaunchedEffect(Unit) {
        runCatching { load() }
            .onSuccess { list -> diffs = list.filter { !it.patch.isNullOrEmpty() || it.additions > 0 || it.deletions > 0 } }
            .onFailure { error = it.message ?: "Failed to load changes" }
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Changes") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when {
                    error != null -> CenteredMessage("Error", error!!)
                    diffs == null -> CircularProgressIndicator()
                    diffs!!.isEmpty() -> CenteredMessage("No changes", "This session hasn't touched any files.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(diffs!!, key = { it.file ?: it.hashCode().toString() }) { file ->
                            DiffFileRow(file) { selected = file }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    selected?.let { file -> DiffFileDetail(file, onDismiss = { selected = null }) }
}

@Composable
private fun DiffFileRow(file: SessionFileDiff, onClick: () -> Unit) {
    val add = Color(0xFF1F9550)
    val del = Color(0xFFCC3333)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (file.status) { "added" -> "＋"; "deleted" -> "－"; else -> "✎" },
            color = when (file.status) { "added" -> add; "deleted" -> del; else -> Color(0xFFE8951F) },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            file.file ?: "?",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (file.additions > 0) Text("+${file.additions}", color = add, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        if (file.deletions > 0) { Spacer(Modifier.width(6.dp)); Text("−${file.deletions}", color = del, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
    }
}

/** One file's full colored diff on its own screen, selectable + copyable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffFileDetail(file: SessionFileDiff, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text((file.file ?: "Diff").substringAfterLast('/'), maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        },
                        actions = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("diff", file.patch ?: ""))
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                        },
                    )
                },
            ) { pad ->
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Box(
                        Modifier.padding(pad).fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(coloredDiff(file.patch ?: "", maxLines = 4000), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Colors a unified diff: green additions, red deletions, dimmed headers. Bounded. */
private fun coloredDiff(patch: String, maxLines: Int = 1200): AnnotatedString = buildAnnotatedString {
    val add = SpanStyle(color = Color(0xFF1F9550), background = Color(0x1F1F9550))
    val del = SpanStyle(color = Color(0xFFCC3333), background = Color(0x1FCC3333))
    val hunk = SpanStyle(color = Color(0xFF2D7FF9))
    val dim = SpanStyle(color = Color(0xFF999999))
    val lines = patch.split("\n")
    for (line in lines.take(maxLines)) {
        val style = when {
            line.startsWith("+") && !line.startsWith("+++") -> add
            line.startsWith("-") && !line.startsWith("---") -> del
            line.startsWith("@@") -> hunk
            line.startsWith("diff ") || line.startsWith("index ") ||
                line.startsWith("+++") || line.startsWith("---") -> dim
            else -> SpanStyle()
        }
        withStyle(style) { append(line); append("\n") }
    }
    if (lines.size > maxLines) {
        withStyle(dim) { append("… ${lines.size - maxLines} more lines") }
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

/** The agent's task list above the composer — a collapsible checklist with progress. */
/** A compact one-line "Tasks n/m" pill above the composer; tapping it opens the
 *  full checklist in a dialog (mobile: don't inline the whole list). */
@Composable
private fun TodoPill(todos: List<TodoItem>, onTap: () -> Unit) {
    val done = todos.count { it.done }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "☑ Tasks $done/${todos.size}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text("▲", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TodoDialog(todos: List<TodoItem>, onDismiss: () -> Unit) {
    val done = todos.count { it.done }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tasks $done/${todos.size}") },
        text = {
            LazyColumn {
                items(todos, key = { it.content }) { todo ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        val (glyph, color) = when (todo.status) {
                            "completed" -> "✓" to androidx.compose.ui.graphics.Color(0xFF1F9550)
                            "in_progress" -> "◐" to androidx.compose.ui.graphics.Color(0xFF2D7FF9)
                            "cancelled" -> "✕" to MaterialTheme.colorScheme.onSurfaceVariant
                            else -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                            color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Composer(state: SessionUiState, viewModel: SessionViewModel) {
    var text by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Pair<Bitmap, PromptAttachment>>>(emptyList()) }
    var fileAttachments by remember { mutableStateOf<List<PromptAttachment>>(emptyList()) }
    var showFilePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch { attachments = uris.mapNotNull { loadAttachment(context, it) } }
    }

    val agentChoices = state.agents.filter { it.selectable }.map { it.name }.ifEmpty { listOf("build", "plan") }

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
            // Agent + model pickers on their own line, so the field takes the width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    FilledTonalButton(
                        onClick = { showAgentMenu = true },
                        modifier = Modifier.testTag("composer.agent"),
                    ) {
                        Text(
                            state.agentName.replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(expanded = showAgentMenu, onDismissRequest = { showAgentMenu = false }) {
                        agentChoices.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name.replaceFirstChar { it.uppercase() }) },
                                onClick = { viewModel.selectAgent(name); showAgentMenu = false },
                                trailingIcon = { if (name == state.agentName) Text("✓") },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.testTag("composer.model"),
                ) {
                    Text(viewModel.modelLabel(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.size(6.dp))
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(attachments, key = { it.second.url.hashCode() }) { (bmp, att) ->
                        Box {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { attachments = attachments.filter { it.second.url != att.url } },
                                modifier = Modifier.size(22.dp).align(Alignment.TopEnd),
                            ) {
                                Text("✕", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
            }
            if (fileAttachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(fileAttachments, key = { it.sourcePath ?: it.filename }) { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(16.dp),
                                )
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text("📄 ${file.filename}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            IconButton(
                                onClick = { fileAttachments = fileAttachments.filter { it !== file } },
                                modifier = Modifier.size(20.dp),
                            ) { Text("✕", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.testTag("composer.attach"),
                ) {
                    Text("📷")
                }
                IconButton(
                    onClick = { showFilePicker = true },
                    modifier = Modifier.testTag("composer.file"),
                ) {
                    Text("📎")
                }
                if (state.commands.isNotEmpty()) {
                    IconButton(
                        onClick = { showCommands = true },
                        modifier = Modifier.testTag("composer.commands"),
                    ) {
                        Text("/", style = MaterialTheme.typography.titleLarge)
                    }
                }
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
                val canSend = (text.isNotBlank() || attachments.isNotEmpty() || fileAttachments.isNotEmpty()) &&
                    !state.sending && state.modelID.isNotEmpty()
                IconButton(
                    onClick = {
                        viewModel.send(
                            text,
                            attachments.map { it.second } + fileAttachments,
                            onCleared = { text = ""; attachments = emptyList(); fileAttachments = emptyList() },
                            restore = { text = it },
                        )
                    },
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

    if (showFilePicker) {
        FilePickerDialog(
            server = viewModel.server,
            startPath = viewModel.directory,
            onPick = { entry ->
                val parent = entry.absolute.substringBeforeLast('/', "").ifEmpty { "/" }
                scope.launch {
                    val content = runCatching { viewModel.server.readFile(parent, entry.name) }.getOrDefault("")
                    if (content.isNotEmpty()) {
                        fileAttachments = fileAttachments + PromptAttachment(
                            mime = "text/plain",
                            filename = entry.name,
                            url = "file://${entry.absolute}",
                            sourcePath = entry.absolute,
                            sourceContent = content,
                        )
                    }
                }
            },
            onDismiss = { showFilePicker = false },
        )
    }

    if (showCommands) {
        AlertDialog(
            onDismissRequest = { showCommands = false },
            title = { Text("Commands") },
            text = {
                Column {
                    state.commands.forEach { cmd ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showCommands = false; viewModel.runCommand(cmd.name) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "/${cmd.name}",
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            cmd.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCommands = false }) { Text("Cancel") } },
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
private fun MessageList(
    state: SessionUiState,
    onRevert: (String) -> Unit,
    onSelect: (studio.eugenezakharov.opencode.api.models.MessageWithParts) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                adapter = MessageAdapter().apply { this.onRevert = onRevert; this.onSelect = onSelect }
                clipToPadding = false
                setPadding(0, 8, 0, 8)
            }
        },
        update = { recycler ->
            val adapter = recycler.adapter as MessageAdapter
            adapter.onRevert = onRevert
            adapter.onSelect = onSelect
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

/** Decodes a picked image Uri into a thumbnail bitmap + a JPEG data-URL attachment. */
private suspend fun loadAttachment(
    context: android.content.Context,
    uri: android.net.Uri,
): Pair<Bitmap, PromptAttachment>? = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    Pair(bmp, PromptAttachment("image/jpeg", "image.jpg", "data:image/jpeg;base64,$b64"))
}
