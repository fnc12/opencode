package studio.eugenezakharov.opencode.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessagePart

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

    /** Session metadata changed (`session.updated`). */
    data class SessionUpdated(val sessionID: String) : ServerEvent

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
                "session.updated" -> SessionUpdated(str("sessionID") ?: return Other(type))
                else -> Other(type)
            }
        }
    }
}
