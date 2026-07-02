package studio.eugenezakharov.opencode.api

import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.api.models.PermissionRequest
import studio.eugenezakharov.opencode.api.models.QuestionRequest

/**
 * Holds the live state of one session's conversation and folds SSE events into
 * it. Seeded with the REST snapshot via [setInitial], then [apply] upserts /
 * removes messages and parts and appends streaming text deltas — mirroring how
 * the web client and iOS app consume the event stream.
 *
 * Hermetic and side-effect free (no Android dependencies) so it can be unit
 * tested directly. The owning ViewModel observes [messages]/[revision]/[status]
 * and republishes them as Compose state. Mirrors iOS `SessionStore`.
 */
class SessionStore {
    enum class StreamStatus { IDLE, CONNECTING, LIVE, RECONNECTING }

    private val _messages = mutableListOf<MessageWithParts>()
    /** A defensive copy of the current ordered message list. */
    val messages: List<MessageWithParts> get() = _messages.map { it.copy(parts = it.parts.toMutableList()) }

    /**
     * True while an assistant message is still generating — i.e. there is an
     * assistant message whose `time.completed` is null. Drives the Stop button
     * in the session top bar (#29). Mirrors iOS `SessionStore.isBusy`.
     */
    val isBusy: Boolean
        get() = _messages.any { it.info is MessageInfo.Assistant && (it.info as MessageInfo.Assistant).completed == null }

    var status: StreamStatus = StreamStatus.IDLE
        private set

    /** Bumped on every applied change so observers can react (e.g. auto-scroll). */
    var revision: Int = 0
        private set

    private val _pendingPermissions = mutableListOf<PermissionRequest>()
    /** Pending permission requests for this session (the agent is blocked on them). */
    val pendingPermissions: List<PermissionRequest> get() = _pendingPermissions.toList()

    private val _pendingQuestions = mutableListOf<QuestionRequest>()
    /** Pending questions the agent is asking for this session (it is blocked on them). */
    val pendingQuestions: List<QuestionRequest> get() = _pendingQuestions.toList()

    private var _todos = listOf<studio.eugenezakharov.opencode.api.models.TodoItem>()
    /** The agent's current task list (shown as a checklist above the composer). */
    val todos: List<studio.eugenezakharov.opencode.api.models.TodoItem> get() = _todos

    fun setInitialTodos(todos: List<studio.eugenezakharov.opencode.api.models.TodoItem>) {
        _todos = todos
        onChange?.invoke()
    }

    /** Listener invoked after any state change (messages/permissions/questions/status/revision). */
    var onChange: (() -> Unit)? = null

    fun setInitial(initial: List<MessageWithParts>) {
        _messages.clear()
        _messages.addAll(initial.sortedBy { it.info.created })
        revision += 1
        onChange?.invoke()
    }

    /** Seeds the pending permissions (from `GET /permission`, filtered to this session). */
    fun setInitialPermissions(permissions: List<PermissionRequest>) {
        _pendingPermissions.clear()
        _pendingPermissions.addAll(permissions)
        revision += 1
        onChange?.invoke()
    }

    /** Drops a permission locally (optimistically, after the user answers it). */
    fun dismissPermission(id: String) {
        if (_pendingPermissions.removeAll { it.id == id }) {
            revision += 1
            onChange?.invoke()
        }
    }

    /** Seeds the pending questions (from `GET /question`, filtered to this session). */
    fun setInitialQuestions(questions: List<QuestionRequest>) {
        _pendingQuestions.clear()
        _pendingQuestions.addAll(questions)
        revision += 1
        onChange?.invoke()
    }

    /** Drops a question locally (optimistically, after the user answers/rejects it). */
    fun dismissQuestion(id: String) {
        if (_pendingQuestions.removeAll { it.id == id }) {
            revision += 1
            onChange?.invoke()
        }
    }

    fun setStatus(newStatus: StreamStatus) {
        status = newStatus
        onChange?.invoke()
    }

    /** Folds one event into the conversation if it targets [sessionID]. */
    fun apply(event: ServerEvent, sessionID: String) {
        val changed = when (event) {
            is ServerEvent.MessageUpdated -> if (event.sessionID == sessionID) {
                upsertMessage(event.info); true
            } else false

            is ServerEvent.PartUpdated -> if (event.sessionID == sessionID) {
                upsertPart(event.part); true
            } else false

            is ServerEvent.PartDelta -> if (event.sessionID == sessionID) {
                appendDelta(event); true
            } else false

            is ServerEvent.PartRemoved -> if (event.sessionID == sessionID) {
                _messages.firstOrNull { it.id == event.messageID }
                    ?.parts?.removeAll { it.id == event.partID }
                true
            } else false

            is ServerEvent.MessageRemoved -> if (event.sessionID == sessionID) {
                _messages.removeAll { it.id == event.messageID }; true
            } else false

            is ServerEvent.PermissionAsked -> if (event.request.sessionID == sessionID) {
                if (_pendingPermissions.none { it.id == event.request.id }) {
                    _pendingPermissions.add(event.request)
                }
                true
            } else false

            is ServerEvent.PermissionReplied -> if (event.sessionID == sessionID) {
                _pendingPermissions.removeAll { it.id == event.requestID }; true
            } else false

            is ServerEvent.QuestionAsked -> if (event.request.sessionID == sessionID) {
                if (_pendingQuestions.none { it.id == event.request.id }) {
                    _pendingQuestions.add(event.request)
                }
                true
            } else false

            is ServerEvent.QuestionResolved -> if (event.sessionID == sessionID) {
                _pendingQuestions.removeAll { it.id == event.requestID }; true
            } else false

            is ServerEvent.TodoUpdated -> if (event.sessionID == sessionID) {
                _todos = event.todos; true
            } else false

            else -> false // SessionUpdated / Other: no state change
        }
        if (changed) {
            revision += 1
            onChange?.invoke()
        }
    }

    private fun upsertMessage(info: studio.eugenezakharov.opencode.api.models.MessageInfo) {
        val i = _messages.indexOfFirst { it.id == info.id }
        if (i >= 0) {
            // Metadata only; created time (and thus order) is stable.
            _messages[i] = _messages[i].copy(info = info)
        } else {
            _messages.add(MessageWithParts(info, mutableListOf()))
            // Order can only change when a message is added — sort here, not per delta.
            _messages.sortBy { it.info.created }
        }
    }

    private fun upsertPart(part: MessagePart) {
        val i = _messages.indexOfFirst { it.id == part.messageID }
        if (i < 0) return
        val parts = _messages[i].parts
        val j = parts.indexOfFirst { it.id == part.id }
        if (j >= 0) parts[j] = part else parts.add(part)
    }

    private fun appendDelta(delta: ServerEvent.PartDelta) {
        if (delta.field != "text") return // only text streams visibly for now
        val i = _messages.indexOfFirst { it.id == delta.messageID }
        if (i < 0) return
        val parts = _messages[i].parts
        val j = parts.indexOfFirst { it.id == delta.partID }
        if (j >= 0) {
            parts[j] = parts[j].appendingText(delta.delta)
        } else {
            // Delta arrived before the first snapshot: synthesize a text part.
            parts.add(
                MessagePart(
                    id = delta.partID,
                    sessionID = delta.sessionID,
                    messageID = delta.messageID,
                    type = "text",
                    content = PartContent.Text(delta.delta),
                ),
            )
        }
    }
}
