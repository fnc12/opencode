package studio.eugenezakharov.opencode.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart
import studio.eugenezakharov.opencode.api.models.PermissionRequest
import studio.eugenezakharov.opencode.api.models.QuestionRequest

/**
 * A decoded record from the OpenCode server SSE stream (`GET /global/event`).
 *
 * The global stream wraps each event in `payload` alongside `directory`/`project`;
 * the instance stream (`/event`) is flat. We descend into `payload` when present
 * so both forms decode. We only model the events a live session view needs;
 * anything else (including `sync`) becomes [Other] so an unknown event never
 * aborts the stream. Mirrors iOS `ServerEvent`.
 */
sealed interface ServerEvent {
    /** A message's metadata was created or changed (`message.updated`). */
    data class MessageUpdated(val sessionID: String, val info: MessageInfo) : ServerEvent

    /** A full snapshot of a single part (`message.part.updated`). */
    data class PartUpdated(val sessionID: String, val part: MessagePart) : ServerEvent

    /** An incremental append to one field of a part (`message.part.delta`). */
    data class PartDelta(
        val sessionID: String,
        val messageID: String,
        val partID: String,
        val field: String,
        val delta: String,
    ) : ServerEvent

    /** A part was removed (`message.part.removed`). */
    data class PartRemoved(val sessionID: String, val messageID: String, val partID: String) : ServerEvent

    /** A message was removed (`message.removed`). */
    data class MessageRemoved(val sessionID: String, val messageID: String) : ServerEvent

    /** Session metadata changed (`session.updated`) — carries the revert boundary. */
    data class SessionUpdated(val sessionID: String, val revertMessageID: String? = null) : ServerEvent

    /** The agent is asking permission to act (`permission.v2.asked`). */
    data class PermissionAsked(val request: PermissionRequest) : ServerEvent

    /** A permission request was answered/cleared (`permission.v2.replied`). */
    data class PermissionReplied(val sessionID: String, val requestID: String) : ServerEvent

    /** The agent is asking the user a question (`question.v2.asked`). */
    data class QuestionAsked(val request: QuestionRequest) : ServerEvent

    /** A question was answered or rejected (`question.v2.replied` / `.rejected`). */
    data class QuestionResolved(val sessionID: String, val requestID: String) : ServerEvent

    /** The session's todo list changed (`todo.updated`). */
    data class TodoUpdated(
        val sessionID: String,
        val todos: List<studio.eugenezakharov.opencode.api.models.TodoItem>,
    ) : ServerEvent

    /** Any event type we don't model (including `sync`). */
    data class Other(val type: String) : ServerEvent

    companion object {
        /**
         * Decodes one SSE `data:` payload into a [ServerEvent]. Returns null if
         * the JSON is malformed or lacks a `type`; unknown/`sync` types decode
         * to [Other] rather than null so the caller can simply ignore them.
         */
        fun decode(json: Json, body: String): ServerEvent? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            // Descend into `payload` (global stream) when present; else flat (instance stream).
            val event = root["payload"]?.jsonObject ?: root
            val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return null
            val props = event["properties"]?.jsonObject ?: JsonObject(emptyMap())

            fun str(key: String): String? = props[key]?.jsonPrimitive?.contentOrNull

            return when (type) {
                "message.updated" -> {
                    val sid = str("sessionID") ?: return Other(type)
                    val info = props["info"]?.jsonObject?.let { MessageInfo.from(it) } ?: return Other(type)
                    MessageUpdated(sid, info)
                }
                "message.part.updated" -> {
                    val sid = str("sessionID") ?: return Other(type)
                    val part = props["part"]?.jsonObject?.let { MessagePart.from(it) } ?: return Other(type)
                    PartUpdated(sid, part)
                }
                "message.part.delta" -> {
                    PartDelta(
                        sessionID = str("sessionID") ?: return Other(type),
                        messageID = str("messageID") ?: return Other(type),
                        partID = str("partID") ?: return Other(type),
                        field = str("field") ?: return Other(type),
                        delta = str("delta") ?: "",
                    )
                }
                "message.part.removed" -> PartRemoved(
                    sessionID = str("sessionID") ?: return Other(type),
                    messageID = str("messageID") ?: return Other(type),
                    partID = str("partID") ?: return Other(type),
                )
                "message.removed" -> MessageRemoved(
                    sessionID = str("sessionID") ?: return Other(type),
                    messageID = str("messageID") ?: return Other(type),
                )
                "session.updated" -> {
                    val info = props["info"] as? kotlinx.serialization.json.JsonObject
                    val sid = (info?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        ?: str("sessionID") ?: return Other(type)
                    val revertID = ((info?.get("revert") as? kotlinx.serialization.json.JsonObject)
                        ?.get("messageID") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    SessionUpdated(sid, revertID)
                }
                // Accept both v2 and older v1 (`permission.asked`) names so prompts
                // surface against any server; `from` decodes both field shapes.
                "permission.v2.asked", "permission.asked" -> {
                    val request = PermissionRequest.from(props) ?: return Other(type)
                    PermissionAsked(request)
                }
                "permission.v2.replied", "permission.replied" -> PermissionReplied(
                    sessionID = str("sessionID") ?: "",
                    requestID = str("requestID") ?: return Other(type),
                )
                "question.v2.asked" -> {
                    // The event properties ARE the question request (id, sessionID, questions…).
                    val request = QuestionRequest.from(props) ?: return Other(type)
                    QuestionAsked(request)
                }
                "question.v2.replied", "question.v2.rejected" -> QuestionResolved(
                    sessionID = str("sessionID") ?: return Other(type),
                    requestID = str("requestID") ?: return Other(type),
                )
                "todo.updated" -> TodoUpdated(
                    sessionID = str("sessionID") ?: return Other(type),
                    todos = runCatching {
                        json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                studio.eugenezakharov.opencode.api.models.TodoItem.serializer(),
                            ),
                            props["todos"] ?: kotlinx.serialization.json.JsonArray(emptyList()),
                        )
                    }.getOrDefault(emptyList()),
                )
                else -> Other(type)
            }
        }
    }
}
