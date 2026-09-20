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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.snapshots.SnapshotStateMap
import studio.eugenezakharov.opencode.api.models.QuestionItem
import studio.eugenezakharov.opencode.api.models.QuestionRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
    val recyclerRef = remember { mutableStateOf<RecyclerView?>(null) }
    var showShareMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // While this session is on screen (RESUMED), suppress its own foreground
    // pushes (e.g. "session finished") — the user is already looking at it.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, session.id) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    studio.eugenezakharov.opencode.push.ShubatMessagingService.activeSessionId = session.id
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    if (studio.eugenezakharov.opencode.push.ShubatMessagingService.activeSessionId == session.id) {
                        studio.eugenezakharov.opencode.push.ShubatMessagingService.activeSessionId = null
                    }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (studio.eugenezakharov.opencode.push.ShubatMessagingService.activeSessionId == session.id) {
                studio.eugenezakharov.opencode.push.ShubatMessagingService.activeSessionId = null
            }
        }
    }

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
                    // Only when the session actually changed files — the top
                    // bar is tight, don't spend a slot on an empty screen.
                    if (state.hasDiff) {
                        IconButton(
                            onClick = { showDiff = true },
                            modifier = Modifier.testTag("session.diff"),
                        ) {
                            Text("±", style = MaterialTheme.typography.titleMedium)
                        }
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
                    val revertID = state.revertMessageID
                    if (revertID != null) {
                        val idx = state.messages.indexOfFirst { it.id == revertID }
                        val count = if (idx >= 0) state.messages.size - idx else 0
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { viewModel.restore() }
                                .background(Color(0xFFE8951F).copy(alpha = 0.18f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "↩ $count message${if (count == 1) "" else "s"} reverted",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB4700F),
                            )
                            Spacer(Modifier.weight(1f))
                            Text("Restore", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFB4700F))
                        }
                    }
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
                    // NOTE: the question dock is NOT here — it rides the transcript as
                    // the list's LAST ROW (see MessageAdapter.setFooter / SessionFooter),
                    // so scrolling up moves it away with the content instead of the
                    // messages sliding under a fixed dock and overlapping it.
                    if (state.runningTools.isNotEmpty()) {
                        RunningToolsStrip(state.runningTools)
                    }
                    Composer(state = state, viewModel = viewModel)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                // A shimmering placeholder transcript reads better than a bare
                // spinner while the newest page loads (mirrors iOS).
                state.loading -> MessageSkeleton()
                state.error != null -> CenteredMessage(
                    "Error", state.error!!,
                    actionLabel = "Retry", onAction = { viewModel.retry() },
                )
                state.messages.isEmpty() && state.pendingQuestions.isEmpty() ->
                    CenteredMessage("No Messages", "This session has no messages yet")
                else -> MessageList(
                    state,
                    onRevert = { viewModel.revert(it) },
                    onSelect = { detailMessage = it },
                    onLoadOlder = { viewModel.loadOlder() },
                    onQuestionReply = { request, answers -> viewModel.replyQuestion(request, answers) },
                    onQuestionReject = { request -> viewModel.rejectQuestion(request) },
                    recyclerRef = recyclerRef,
                )
            }
            val last = state.messages.lastOrNull()
            val streaming = last?.info is studio.eugenezakharov.opencode.api.models.MessageInfo.Assistant &&
                last.parts.any { p ->
                    p.isVisible && (p.content as? studio.eugenezakharov.opencode.api.models.PartContent.Text)?.text?.isNotBlank() == true
                }
            // A "busy" turn is likely *stuck* (e.g. an unanswered permission on an
            // old server) when it's produced no text for a while. Re-evaluate on a
            // 30s tick since a parked turn emits no events. Then show a cancel hint
            // instead of endless dots — the Stop button already aborts.
            var stuckTick by remember { mutableStateOf(0L) }
            LaunchedEffect(state.isBusy) {
                while (state.isBusy) { kotlinx.coroutines.delay(30_000); stuckTick = System.currentTimeMillis() }
            }
            val stuck = remember(stuckTick, state.isBusy, last) {
                val a = last?.info as? studio.eugenezakharov.opencode.api.models.MessageInfo.Assistant
                state.isBusy && a != null && a.completed == null &&
                    System.currentTimeMillis() - a.created > 180_000
            }
            // Fade the progress indicator IN/OUT rather than popping it — so hitting
            // Stop eases the dots away instead of vanishing them. Mirrors iOS.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.isBusy && !streaming,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                if (stuck) {
                    Text(
                        "⚠ This turn looks stuck — tap ■ to cancel",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(start = 16.dp, bottom = 12.dp)
                            .testTag("session.stuck"),
                    )
                } else {
                    TypingDots(Modifier.padding(start = 16.dp, bottom = 10.dp))
                }
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
/** The list's footer content — the pending question dock(s), rendered right after
 *  the newest message so they scroll WITH the transcript. Hosted by
 *  [MessageAdapter] in a `ComposeView` last row. Mirrors iOS `SessionFooter`.
 *  Selections are hoisted in ([selectionsFor]) so they survive the ComposeView
 *  being recycled when the footer scrolls off. */
@Composable
internal fun SessionFooter(
    questions: List<QuestionRequest>,
    selectionsFor: (QuestionRequest) -> SnapshotStateMap<String, Set<String>>,
    onReply: (QuestionRequest, List<List<String>>) -> Unit,
    onReject: (QuestionRequest) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        questions.forEach { request ->
            QuestionDock(
                request = request,
                selections = selectionsFor(request),
                onReply = { answers -> onReply(request, answers) },
                onReject = { onReject(request) },
            )
        }
    }
}

/**
 * A pending agent question, riding the transcript as its last row. A single-question
 * request lays out directly; a multi-question request is shown as SLIDES (one
 * question per screen, à la Claude): a "N / total" counter + dots, swipe between
 * them, and answering a single-select question auto-advances to the next.
 * Skip/Submit stay pinned below, always reachable. `selections` is hoisted so it
 * survives the hosting ComposeView being recycled. Mirrors iOS `QuestionDock`.
 */
@Composable
private fun QuestionDock(
    request: QuestionRequest,
    selections: SnapshotStateMap<String, Set<String>>,
    onReply: (List<List<String>>) -> Unit,
    onReject: () -> Unit,
) {
    val info = Color(0xFF2D7FF9)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val isMulti = request.questions.size > 1
    val pagerState = rememberPagerState(pageCount = { request.questions.size })
    val scope = rememberCoroutineScope()

    val pick: (QuestionItem, String) -> Unit = { question, label ->
        val current = selections[question.key] ?: emptySet()
        selections[question.key] = if (question.allowsMultiple) {
            if (label in current) current - label else current + label
        } else {
            setOf(label) // radio
        }
        // Claude-style slides: a single-select answer advances to the next question.
        if (isMulti && !question.allowsMultiple) {
            val idx = request.questions.indexOfFirst { it.key == question.key }
            if (idx >= 0 && idx + 1 < request.questions.size) {
                scope.launch { pagerState.animateScrollToPage(idx + 1) }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(width = 1.dp, color = info.copy(alpha = 0.35f), shape = shape)
            .background(color = info.copy(alpha = 0.10f), shape = shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "? Question",
                color = info,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (isMulti) {
                Text(
                    "${pagerState.currentPage + 1} / ${request.questions.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        if (isMulti) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                verticalAlignment = Alignment.Top,
            ) { page ->
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    QuestionSlide(request.questions[page], selections, info, pick)
                }
            }
            Spacer(Modifier.size(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(request.questions.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (active) 8.dp else 6.dp)
                            .background(if (active) info else info.copy(alpha = 0.3f), CircleShape),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        } else {
            request.questions.firstOrNull()?.let { QuestionSlide(it, selections, info, pick) }
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

/** One question inside the dock — header, prompt, and its selectable options. */
@Composable
private fun QuestionSlide(
    question: QuestionItem,
    selections: SnapshotStateMap<String, Set<String>>,
    info: Color,
    onPick: (QuestionItem, String) -> Unit,
) {
    Column {
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
                    .clickable { onPick(question, option.label) }
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

    // Bottom inset = MAX(ime, navigationBars), NOT their sum. The IME inset already
    // spans the nav-bar strip when the keyboard is up, so stacking
    // .navigationBarsPadding().imePadding() double-counted it and jumped the
    // composer (and the transcript) up. Reading WindowInsets.ime here keeps the
    // padding animating in step with the keyboard. See [ComposerInsets].
    val density = LocalDensity.current
    val bottomInset = with(density) {
        ComposerInsets.bottomInsetPx(
            imeBottomPx = WindowInsets.ime.getBottom(this),
            navBarBottomPx = WindowInsets.navigationBars.getBottom(this),
        ).toDp()
    }
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = bottomInset)
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
                // Attach / command actions collapse into one "+" menu so the row
                // isn't crowded (and stays roomy when the field grows). Matches iOS.
                var showAttachMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier.testTag("composer.plus"),
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineSmall)
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("📷  Photo") },
                            onClick = {
                                showAttachMenu = false
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.testTag("composer.attach"),
                        )
                        DropdownMenuItem(
                            text = { Text("📎  File") },
                            onClick = { showAttachMenu = false; showFilePicker = true },
                            modifier = Modifier.testTag("composer.file"),
                        )
                        if (state.commands.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("/  Command") },
                                onClick = { showAttachMenu = false; showCommands = true },
                                modifier = Modifier.testTag("composer.commands"),
                            )
                        }
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
    onLoadOlder: () -> Unit,
    onQuestionReply: (QuestionRequest, List<List<String>>) -> Unit = { _, _ -> },
    onQuestionReject: (QuestionRequest) -> Unit = {},
    recyclerRef: androidx.compose.runtime.MutableState<RecyclerView?>? = null,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                adapter = MessageAdapter().apply {
                    this.onRevert = onRevert; this.onSelect = onSelect
                    this.onQuestionReply = onQuestionReply; this.onQuestionReject = onQuestionReject
                }
                clipToPadding = false
                setPadding(0, 8, 0, 8)
                // Keep insert/remove animations (a new step slides in) but drop the
                // change cross-fade, so a streaming text delta rebinding a row
                // doesn't flicker on every token.
                (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
                    ?.supportsChangeAnimations = false
                // Instagram-style preload: page in older history while the user is
                // still ~1.5 screens from the top, so it lands before they reach it.
                // Only fires on a real scroll (dy != 0 while dragging/settling), so
                // the initial fill can't auto-trigger it. loadOlder() is guarded by
                // an in-flight flag, so firing every scroll frame is safe. Inserting
                // older rows above keeps the viewport stable — RecyclerView anchors
                // to the first visible child across a top insert (DiffUtil-driven).
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        if (dy >= 0) return // only when scrolling up (toward older)
                        val lm = rv.layoutManager as LinearLayoutManager
                        val visibleThreshold = lm.childCount * 3 / 2 // ~1.5 screens of rows
                        if (lm.findFirstVisibleItemPosition() <= visibleThreshold) onLoadOlder()
                    }
                })
                recyclerRef?.value = this
            }
        },
        update = { recycler ->
            val adapter = recycler.adapter as MessageAdapter
            adapter.onRevert = onRevert
            adapter.onSelect = onSelect
            adapter.onQuestionReply = onQuestionReply
            adapter.onQuestionReject = onQuestionReject
            val lm = recycler.layoutManager as LinearLayoutManager
            val atBottom = lm.findLastVisibleItemPosition() >= adapter.itemCount - 2 || adapter.itemCount == 0
            // Hide messages after the revert boundary.
            val revertID = state.revertMessageID
            val visible = if (revertID != null) {
                val idx = state.messages.indexOfFirst { it.id == revertID }
                if (idx >= 0) state.messages.subList(0, idx) else state.messages
            } else {
                state.messages
            }
            val changed = adapter.submit(visible)
            // The question dock is the LAST ROW (a footer), so it scrolls with the
            // transcript; keep it on screen when the user is at the newest message.
            val footerChanged = adapter.setFooter(state.pendingQuestions)
            if ((changed || footerChanged) && atBottom && adapter.itemCount > 0) {
                recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
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
        // Just the dot — the "Live" label crowded the toolbar. The green dot alone
        // reads as "connected".
        SessionStore.StreamStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "●",
                color = androidx.compose.ui.graphics.Color(0xFF4CD964),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("session.live"),
            )
            Spacer(Modifier.width(8.dp))
        }
        // Spinner alone (no text) — transient anyway.
        SessionStore.StreamStatus.CONNECTING,
        SessionStore.StreamStatus.RECONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Decodes a picked image Uri into a thumbnail bitmap + a JPEG data-URL attachment.
 *  Internal so an instrumented test can drive the decode with a real image Uri
 *  (the paste-image UI path can't easily seed the clipboard with a content Uri). */
internal suspend fun loadAttachment(
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


/** One-line strip above the composer: spinner + what's running + for how long.
 *  Shown ONLY while something actually runs (its absence means the agent is
 *  generating text, not waiting on a process). Mirrors iOS `RunningToolsPill`. */
@Composable
private fun RunningToolsStrip(tools: List<studio.eugenezakharov.opencode.api.RunningTool>) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tools.firstOrNull()?.id) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            now = System.currentTimeMillis()
        }
    }
    val first = tools.first()
    val label = buildList {
        add(first.name)
        first.title?.takeIf { it.isNotEmpty() }?.let { add(it.take(60)) }
        first.startedMs?.let {
            val s = ((now - it) / 1000).toLong().coerceAtLeast(0)
            add(if (s < 60) "${s}s" else "${s / 60}m")
        }
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("session.runningTools"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tools.size > 1) {
            Text(
                "${tools.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
