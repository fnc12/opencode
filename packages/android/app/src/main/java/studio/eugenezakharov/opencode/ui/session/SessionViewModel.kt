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
    val sending: Boolean = false,
    val sendError: String? = null,
    // Permission requests (#27): the agent is blocked until these are answered.
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    // Agent questions (#28): the agent is blocked until these are answered/skipped.
    val pendingQuestions: List<QuestionRequest> = emptyList(),
)

/**
 * Drives one session's live view: seeds history via REST, then consumes the
 * `GET /global/event` SSE stream (reconnecting with exponential backoff),
 * folding events into a [SessionStore]. Also backs the composer: loads
 * providers, remembers the last-used model, and sends prompts. Mirrors iOS
 * `SessionView` + `ComposerView`.
 */
class SessionViewModel(
    private val server: ServerConnection,
    private val session: Session,
    private val prefs: ComposerPrefs,
    /** UI tests inject a synthetic permission so the dock can be driven (mirrors iOS UITEST_PERMISSION). */
    private val injectTestPermission: Boolean = false,
    /** UI tests inject a synthetic question so the dock can be driven (mirrors iOS UITEST_QUESTION). */
    private val injectTestQuestion: Boolean = false,
) : ViewModel() {

    private val store = SessionStore()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        SessionUiState(providerID = prefs.providerID, modelID = prefs.modelID, agentName = prefs.agent),
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
                )
            }
        }
        start()
        loadProviders()
    }

    private fun start() {
        viewModelScope.launch {
            // 1) Seed history.
            val seeded = runCatching { server.messages(session.directory, session.id) }
            seeded.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load messages") }
                return@launch
            }
            store.setInitial(seeded.getOrThrow())
            _state.update { it.copy(loading = false, error = null) }

            // 1b) Seed any permission requests / questions already pending for this session.
            runCatching { server.permissions(session.directory) }.getOrNull()?.let { pending ->
                store.setInitialPermissions(pending.filter { it.sessionID == session.id })
            }
            runCatching { server.questions(session.directory) }.getOrNull()?.let { pending ->
                store.setInitialQuestions(pending.filter { it.sessionID == session.id })
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
    fun send(text: String, onCleared: () -> Unit, restore: (String) -> Unit) {
        val prompt = text.trim()
        val s = _state.value
        if (prompt.isEmpty() || s.modelID.isEmpty() || s.sending) return
        onCleared()
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            runCatching {
                server.sendPrompt(session.directory, session.id, prompt, s.providerID, s.modelID, s.agentName)
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
}
