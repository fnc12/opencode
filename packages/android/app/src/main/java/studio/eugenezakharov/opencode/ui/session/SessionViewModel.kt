package studio.eugenezakharov.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import studio.eugenezakharov.opencode.api.ComposerPrefs
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PermissionRequest
import studio.eugenezakharov.opencode.api.models.ProviderInfo
import studio.eugenezakharov.opencode.api.models.QuestionItem
import studio.eugenezakharov.opencode.api.models.QuestionOption
import studio.eugenezakharov.opencode.api.models.QuestionRequest
import studio.eugenezakharov.opencode.api.models.Session

data class SessionUiState(
    val messages: List<MessageWithParts> = emptyList(),
    val revision: Int = 0,
    val status: SessionStore.StreamStatus = SessionStore.StreamStatus.IDLE,
    // True while an assistant reply is still generating; gates the Stop button (#29).
    val isBusy: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    // Composer state (#16)
    val providers: List<ProviderInfo> = emptyList(),
    val providerID: String = "",
    val modelID: String = "",
    val agents: List<studio.eugenezakharov.opencode.api.models.AgentInfo> = emptyList(),
    val agentName: String = "build",
    val shareUrl: String? = null,
    val sending: Boolean = false,
    val sendError: String? = null,
    // Permission requests (#27): the agent is blocked until these are answered.
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    // Agent questions (#28): the agent is blocked until these are answered/skipped.
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val todos: List<studio.eugenezakharov.opencode.api.models.TodoItem> = emptyList(),
    val commands: List<studio.eugenezakharov.opencode.api.models.CommandInfo> = emptyList(),
    val revertMessageID: String? = null,
    // True while an older page of history is being fetched (scroll-up pagination) —
    // drives the top-of-list loading indicator.
    val loadingOlder: Boolean = false,
)

/**
 * Drives one session's live view: seeds history via REST, then consumes the
 * `GET /global/event` SSE stream (reconnecting with exponential backoff),
 * folding events into a [SessionStore]. Also backs the composer: loads
 * providers, remembers the last-used model, and sends prompts. Mirrors iOS
 * `SessionView` + `ComposerView`.
 */
class SessionViewModel(
    val server: ServerConnection,
    private val session: Session,
    private val prefs: ComposerPrefs,
    /** UI tests inject a synthetic permission so the dock can be driven (mirrors iOS UITEST_PERMISSION). */
    private val injectTestPermission: Boolean = false,
    /** UI tests inject a synthetic question so the dock can be driven (mirrors iOS UITEST_QUESTION). */
    private val injectTestQuestion: Boolean = false,
    /** UI tests inject synthetic todos so the panel can be driven (mirrors iOS UITEST_TODO). */
    private val injectTestTodo: Boolean = false,
    /** On-disk newest-page cache for cache-first paint; null in tests / no-context. */
    private val cache: studio.eugenezakharov.opencode.api.MessageCache? = null,
) : ViewModel() {

    private val store = SessionStore()
    private val json = Json { ignoreUnknownKeys = true }

    // Scroll-up pagination cursor state. The initial seed pulls only the newest
    // page so a huge session (tens of MB of tool output) opens instantly; older
    // pages stream in as the user scrolls up. [oldestCursor] is the `before=`
    // token for the next older page; null + [reachedStart] means the very first
    // message has been reached.
    private var oldestCursor: String? = null
    private var reachedStart = false
    private var loadingOlder = false

    private val _state = MutableStateFlow(
        SessionUiState(
            providerID = prefs.providerID,
            modelID = prefs.modelID,
            agentName = prefs.agent,
            shareUrl = session.share?.url,
        ),
    )
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    init {
        store.onChange = {
            _state.update {
                it.copy(
                    messages = store.messages,
                    revision = store.revision,
                    status = store.status,
                    isBusy = store.isBusy,
                    pendingPermissions = store.pendingPermissions,
                    pendingQuestions = store.pendingQuestions,
                    todos = store.todos,
                    revertMessageID = store.revertMessageID,
                )
            }
        }
        start()
        loadProviders()
    }

    private fun start() {
        viewModelScope.launch {
            // 0) Cache-first paint: show the last-seen newest page from disk right
            // away (the skeleton is skipped once messages are non-empty), then
            // refresh from the network in parallel below.
            if (store.messages.isEmpty()) {
                cache?.load(session.id)?.let { cached ->
                    store.setInitial(cached)
                    store.setRevert(session.revert?.messageID)
                    _state.update { it.copy(loading = false, error = null) }
                }
            }

            // 1) Seed just the newest page — not the whole transcript. A single
            // session can carry tens of MB of tool output; fetching it all blocked
            // the screen for 40s–2min. The newest page renders instantly and older
            // history pages in on scroll-up. (Measured: newest-5 = 32KB/4ms vs the
            // full history = 41MB on the pathological session.)
            val seeded = runCatching {
                server.messagesPage(session.directory, session.id, INITIAL_PAGE_SIZE)
            }
            seeded.onFailure { e ->
                // Keep any cache-painted messages on screen; only dead-end to the
                // error view when there's nothing to show.
                _state.update {
                    if (store.messages.isEmpty()) {
                        it.copy(loading = false, error = e.message ?: "Failed to load messages")
                    } else {
                        it.copy(loading = false)
                    }
                }
                return@launch
            }
            val page = seeded.getOrThrow()
            store.setInitial(page.messages)
            oldestCursor = page.nextCursor
            reachedStart = page.nextCursor == null
            store.setRevert(session.revert?.messageID)
            cache?.save(session.id, page.raw) // seed the next reopen
            _state.update { it.copy(loading = false, error = null) }

            // 1b) Seed any permission requests / questions already pending for this session.
            runCatching { server.permissions(session.directory) }.getOrNull()?.let { pending ->
                store.setInitialPermissions(pending.filter { it.sessionID == session.id })
            }
            runCatching { server.questions(session.directory) }.getOrNull()?.let { pending ->
                store.setInitialQuestions(pending.filter { it.sessionID == session.id })
            }
            runCatching { server.sessionTodos(session.directory, session.id) }.getOrNull()?.let { todos ->
                store.setInitialTodos(todos)
            }
            runCatching { server.commands(session.directory) }.getOrNull()?.let { cmds ->
                _state.update { it.copy(commands = cmds) }
            }
            // UI tests inject synthetic requests (after the seeds, so they win) so the
            // docks can be driven without the server being configured to "ask".
            if (injectTestPermission) {
                store.setInitialPermissions(
                    listOf(
                        PermissionRequest(
                            id = "uitest-perm",
                            sessionID = session.id,
                            action = "bash",
                            resources = listOf("echo hello"),
                        ),
                    ),
                )
            }
            if (injectTestQuestion) {
                store.setInitialQuestions(
                    listOf(
                        QuestionRequest(
                            id = "uitest-q",
                            sessionID = session.id,
                            questions = listOf(
                                QuestionItem(
                                    question = "Which database?",
                                    header = "Pick one",
                                    options = listOf(
                                        QuestionOption("Option A", "the first"),
                                        QuestionOption("Option B", "the second"),
                                    ),
                                    multiple = false,
                                    custom = false,
                                ),
                            ),
                        ),
                    ),
                )
            }
            if (injectTestTodo) {
                store.setInitialTodos(
                    listOf(
                        studio.eugenezakharov.opencode.api.models.TodoItem("Read the AST query schema", "completed", "high"),
                        studio.eugenezakharov.opencode.api.models.TodoItem("Rename qr → qualifiedColumnRefNode across the codebase", "in_progress", "high"),
                        studio.eugenezakharov.opencode.api.models.TodoItem("Run the test suite and verify 623 tests pass", "pending", "medium"),
                    ),
                )
            }

            // 2) Stream live with reconnect/backoff.
            var backoffMs = 500L
            while (isActive) {
                val stream = server.eventStream() ?: break
                store.setStatus(SessionStore.StreamStatus.CONNECTING)
                runCatching {
                    stream.frames().collect { data ->
                        backoffMs = 500L // healthy stream resets backoff
                        store.setStatus(SessionStore.StreamStatus.LIVE)
                        ServerEvent.decode(json, data)?.let { store.apply(it, session.id) }
                    }
                }
                if (!isActive) break
                store.setStatus(SessionStore.StreamStatus.RECONNECTING)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    /**
     * Re-sync the snapshot from the server (messages + pending
     * permissions/questions/todos) without disturbing the live stream, which
     * self-heals via its own backoff loop. Called when the app returns to the
     * foreground so a gap while backgrounded doesn't leave the screen stale.
     */
    fun refresh() {
        // In UI-test mode the docks are driven by injected synthetic data — don't
        // overwrite it with (empty) server truth.
        if (injectTestPermission || injectTestQuestion || injectTestTodo) return
        viewModelScope.launch {
            // Re-seed just the newest page and reset the pagination cursor — a
            // foreground refresh shouldn't re-pull the whole (possibly huge) history.
            runCatching { server.messagesPage(session.directory, session.id, INITIAL_PAGE_SIZE) }.getOrNull()?.let {
                store.setInitial(it.messages)
                oldestCursor = it.nextCursor
                reachedStart = it.nextCursor == null
                store.setRevert(session.revert?.messageID)
                cache?.save(session.id, it.raw)
            }
            runCatching { server.permissions(session.directory) }.getOrNull()?.let { pending ->
                store.setInitialPermissions(pending.filter { it.sessionID == session.id })
            }
            runCatching { server.questions(session.directory) }.getOrNull()?.let { pending ->
                store.setInitialQuestions(pending.filter { it.sessionID == session.id })
            }
            runCatching { server.sessionTodos(session.directory, session.id) }.getOrNull()?.let {
                store.setInitialTodos(it)
            }
        }
    }

    /**
     * Pages in the next older chunk of history (scroll-up). Called by the list as
     * it nears the top — fired ahead of the user reaching the first row so the
     * page lands before they get there. Guarded by an in-flight flag so the many
     * scroll events near the top collapse into one request; a null [oldestCursor]
     * / [reachedStart] means there's nothing older to fetch. Mirrors iOS
     * `SessionView.loadOlder`.
     */
    fun loadOlder() {
        if (injectTestPermission || injectTestQuestion || injectTestTodo) return
        val cursor = oldestCursor
        if (loadingOlder || reachedStart || cursor == null) return
        loadingOlder = true
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            runCatching { server.messagesPage(session.directory, session.id, OLDER_PAGE_SIZE, cursor) }
                .onSuccess { page ->
                    store.prependOlder(page.messages)
                    oldestCursor = page.nextCursor
                    if (page.nextCursor == null) reachedStart = true
                }
            // On failure: leave the cursor untouched so the next scroll near the top
            // retries. No user-facing error for a page fetched speculatively ahead.
            loadingOlder = false
            _state.update { it.copy(loadingOlder = false) }
        }
    }

    /**
     * Re-runs the initial load + stream after a failure (e.g. a seed that timed
     * out on a flaky network). Wired to the Retry button so the error screen
     * isn't a dead end. Safe because [start] returns before the stream loop on
     * the error path, so no second stream is left running.
     */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        start()
    }

    private fun loadProviders() {
        viewModelScope.launch {
            val list = runCatching { server.providers() }.getOrDefault(emptyList())
            _state.update { it.copy(providers = list) }
            if (_state.value.modelID.isEmpty()) autoSelectDefault(list)
        }
        viewModelScope.launch {
            val agents = runCatching { server.agents() }.getOrDefault(emptyList())
            _state.update { it.copy(agents = agents) }
        }
    }

    fun selectAgent(name: String) {
        prefs.agent = name
        _state.update { it.copy(agentName = name) }
    }

    /** Reverts the session to before [messageID]; the boundary arrives over SSE. */
    fun revert(messageID: String) {
        viewModelScope.launch {
            runCatching { server.revertSession(session.directory, session.id, messageID) }
        }
    }

    /** Restores all reverted messages. */
    fun restore() {
        viewModelScope.launch {
            runCatching { server.unrevertSession(session.directory, session.id) }
                .onSuccess { store.setRevert(null) }
        }
    }

    /** The session's working directory — used by the file picker to browse the repo. */
    val directory: String get() = session.directory

    /** Runs a slash command; its expansion + reply stream back over SSE. */
    fun runCommand(name: String) {
        viewModelScope.launch {
            runCatching { server.runCommand(session.directory, session.id, name) }
        }
    }

    /** Creates (or reuses) the public share link; [onLink] gets the URL to share. */
    fun share(onLink: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { server.shareSession(session.directory, session.id) }
                .onSuccess { s ->
                    s.share?.url?.let { url ->
                        _state.update { st -> st.copy(shareUrl = url) }
                        onLink(url)
                    }
                }
                .onFailure { e -> _state.update { st -> st.copy(sendError = e.message ?: "Share failed") } }
        }
    }

    fun unshare() {
        viewModelScope.launch {
            runCatching { server.unshareSession(session.directory, session.id) }
                .onSuccess { _state.update { st -> st.copy(shareUrl = null) } }
        }
    }

    /** Pick a sensible default if none chosen — prefer a free `opencode` model. */
    private fun autoSelectDefault(providers: List<ProviderInfo>) {
        val opencode = providers.firstOrNull { it.id == "opencode" }
        if (opencode != null && opencode.models.isNotEmpty()) {
            val model = opencode.models.firstOrNull { it.id.contains("free") } ?: opencode.models.first()
            selectModel("opencode", model.id)
            return
        }
        val provider = providers.firstOrNull { it.models.isNotEmpty() } ?: return
        selectModel(provider.id, provider.models.first().id)
    }

    fun selectModel(providerID: String, modelID: String) {
        prefs.setModel(providerID, modelID)
        _state.update { it.copy(providerID = providerID, modelID = modelID) }
    }

    /** Human label for the currently selected model (falls back to the id). */
    fun modelLabel(): String {
        val s = _state.value
        if (s.modelID.isEmpty()) return "Model"
        val provider = s.providers.firstOrNull { it.id == s.providerID }
        return provider?.models?.firstOrNull { it.id == s.modelID }?.displayName ?: s.modelID
    }

    /**
     * Sends [text] as a prompt. Clears the input optimistically; the reply (and
     * the echoed user message) arrive over the SSE stream. [onCleared] lets the
     * UI clear its field; [restore] is invoked with the original text on failure.
     */
    fun send(
        text: String,
        attachments: List<studio.eugenezakharov.opencode.api.models.PromptAttachment> = emptyList(),
        onCleared: () -> Unit,
        restore: (String) -> Unit,
    ) {
        val prompt = text.trim()
        val s = _state.value
        if ((prompt.isEmpty() && attachments.isEmpty()) || s.modelID.isEmpty() || s.sending) return
        onCleared()
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            runCatching {
                server.sendPrompt(session.directory, session.id, prompt, s.providerID, s.modelID, s.agentName, attachments)
            }.onSuccess {
                _state.update { it.copy(sending = false) }
            }.onFailure { e ->
                restore(prompt)
                _state.update { it.copy(sending = false, sendError = e.message ?: "Send failed") }
            }
        }
    }

    /**
     * Aborts the running generation for this session (#29). Best-effort: the
     * stream reflects the stop, so there's no optimistic local state to clear.
     * Mirrors iOS `SessionView`'s Stop button.
     */
    fun abort() {
        viewModelScope.launch {
            runCatching { server.abort(session.directory, session.id) }
        }
    }

    /** The session's aggregate file changes (for the "Changes" / diff screen). */
    suspend fun loadDiff(): List<studio.eugenezakharov.opencode.api.models.SessionFileDiff> =
        server.sessionDiff(session.directory, session.id)

    /**
     * Answers a permission request and clears it locally right away. [reply] is
     * `"once"`, `"always"`, or `"reject"`. Mirrors iOS `handleReply`.
     */
    fun replyPermission(request: PermissionRequest, reply: String) {
        store.dismissPermission(request.id) // optimistic
        viewModelScope.launch {
            runCatching { server.replyPermission(session.directory, request.id, reply) }
        }
    }

    /**
     * Answers a question with one array of selected labels per question and
     * clears it locally right away. Mirrors iOS `handleQuestionReply`.
     */
    fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
        store.dismissQuestion(request.id) // optimistic
        viewModelScope.launch {
            runCatching { server.replyQuestion(session.directory, request.id, answers) }
        }
    }

    /** Rejects (skips) a question and clears it locally. Mirrors iOS `handleQuestionReject`. */
    fun rejectQuestion(request: QuestionRequest) {
        store.dismissQuestion(request.id) // optimistic
        viewModelScope.launch {
            runCatching { server.rejectQuestion(session.directory, request.id) }
        }
    }

    companion object {
        /** Newest page fetched on open — small so a huge session opens instantly. */
        private const val INITIAL_PAGE_SIZE = 5

        /** Older pages pulled on scroll-up — a bigger chunk, one round-trip. */
        private const val OLDER_PAGE_SIZE = 20
    }
}
