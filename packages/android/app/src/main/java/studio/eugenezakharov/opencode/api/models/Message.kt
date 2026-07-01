package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A message together with its parts, as returned by
 * `GET /session/{id}/message` and folded by the session store.
 *
 * Mirrors the iOS `MessageWithParts`. `parts` is mutable so the store can fold
 * streaming events in place (the store always reassigns the list afterward).
 */
data class MessageWithParts(
    val info: MessageInfo,
    val parts: MutableList<MessagePart> = mutableListOf(),
) {
    val id: String get() = info.id
}

/** A message's metadata. Tagged by `role` (user / assistant). */
sealed interface MessageInfo {
    val id: String
    val sessionID: String
    val role: String
    val created: Double

    data class User(
        override val id: String,
        override val sessionID: String,
        override val created: Double,
        val agent: String? = null,
    ) : MessageInfo {
        override val role: String get() = "user"
    }

    data class Assistant(
        override val id: String,
        override val sessionID: String,
        override val created: Double,
        val completed: Double? = null,
        val modelID: String = "",
        val providerID: String = "",
        val agent: String = "",
        val cost: Double = 0.0,
        val tokensInput: Int = 0,
        val tokensOutput: Int = 0,
        val error: MessageError? = null,
    ) : MessageInfo {
        override val role: String get() = "assistant"
    }

    companion object {
        /** Parses a message `info` object, dispatching on `role`. Returns null on unknown role. */
        fun from(obj: JsonObject): MessageInfo? {
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionID = obj["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
            val time = obj["time"]?.jsonObject
            val created = time?.get("created")?.jsonPrimitive?.doubleOrNull ?: 0.0
            return when (role) {
                "user" -> User(
                    id = id,
                    sessionID = sessionID,
                    created = created,
                    agent = obj["agent"]?.jsonPrimitive?.contentOrNull,
                )
                "assistant" -> {
                    val tokens = obj["tokens"]?.jsonObject
                    Assistant(
                        id = id,
                        sessionID = sessionID,
                        created = created,
                        completed = time?.get("completed")?.jsonPrimitive?.doubleOrNull,
                        modelID = obj["modelID"]?.jsonPrimitive?.contentOrNull ?: "",
                        providerID = obj["providerID"]?.jsonPrimitive?.contentOrNull ?: "",
                        agent = obj["agent"]?.jsonPrimitive?.contentOrNull ?: "",
                        cost = obj["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        tokensInput = tokens?.get("input")?.jsonPrimitive?.intOrNull ?: 0,
                        tokensOutput = tokens?.get("output")?.jsonPrimitive?.intOrNull ?: 0,
                        error = obj["error"]?.let { MessageError.from(it) },
                    )
                }
                else -> null
            }
        }
    }
}

data class MessageError(
    val name: String,
    val message: String? = null,
) {
    val displayText: String
        get() = message?.takeIf { it.isNotEmpty() } ?: name

    companion object {
        fun from(element: JsonElement): MessageError? {
            val obj = element as? JsonObject ?: return null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val dataMsg = obj["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            val message = dataMsg ?: obj["message"]?.jsonPrimitive?.contentOrNull
            return MessageError(name, message)
        }
    }
}

/**
 * A single message part. The `content` is resolved from `type` and tolerates
 * unknown types (decodes to null). Mirrors iOS `MessagePart` / `PartContent`.
 */
data class MessagePart(
    val id: String,
    val sessionID: String,
    val messageID: String,
    val type: String,
    val content: PartContent? = null,
) {
    /** Returns a copy with `delta` appended to its text (only valid for text parts). */
    fun appendingText(delta: String): MessagePart {
        val existing = (content as? PartContent.Text)?.text ?: ""
        return copy(content = PartContent.Text(existing + delta))
    }

    companion object {
        fun from(obj: JsonObject): MessagePart? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionID = obj["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
            val messageID = obj["messageID"]?.jsonPrimitive?.contentOrNull ?: ""
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: ""
            return MessagePart(id, sessionID, messageID, type, PartContent.from(type, obj))
        }
    }
}

/** Resolved content of a message part. Mirrors iOS `PartContent`. */
sealed interface PartContent {
    data class Text(val text: String) : PartContent
    data class Tool(
        val tool: String,
        val callID: String,
        val status: String,
        val title: String? = null,
        val output: String? = null,
        val error: String? = null,
        /** Stringified `state.input` (filePath, command, description, pattern, …). */
        val input: Map<String, String> = emptyMap(),
        val metadata: ToolMeta? = null,
    ) : PartContent
    data class StepStart(val title: String? = null) : PartContent
    data class StepFinish(val reason: String? = null) : PartContent
    /** A committed change snapshot: the files touched in one assistant turn. */
    data class Patch(val hash: String? = null, val files: List<String> = emptyList()) : PartContent
    /** A referenced file (IDE context attachment), rendered as a compact chip. */
    data class FileRef(val filename: String? = null, val url: String? = null, val mime: String? = null) : PartContent

    companion object {
        fun from(type: String, obj: JsonObject): PartContent? = when (type) {
            "text" -> Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "tool" -> {
                val state = obj["state"]?.jsonObject
                Tool(
                    tool = obj["tool"]?.jsonPrimitive?.contentOrNull ?: "",
                    callID = obj["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                    status = state?.get("status")?.jsonPrimitive?.contentOrNull ?: "",
                    title = state?.get("title")?.jsonPrimitive?.contentOrNull,
                    output = state?.get("output")?.jsonPrimitive?.contentOrNull,
                    error = state?.get("error")?.jsonPrimitive?.contentOrNull,
                    input = parseInput(state?.get("input")),
                    metadata = ToolMeta.from(state?.get("metadata")),
                )
            }
            "step-start" -> StepStart(obj["title"]?.jsonPrimitive?.contentOrNull)
            "step-finish" -> StepFinish(obj["reason"]?.jsonPrimitive?.contentOrNull)
            "patch" -> Patch(
                hash = obj["hash"]?.jsonPrimitive?.contentOrNull,
                files = (obj["files"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            )
            "file" -> FileRef(
                filename = obj["filename"]?.jsonPrimitive?.contentOrNull,
                url = obj["url"]?.jsonPrimitive?.contentOrNull,
                mime = obj["mime"]?.jsonPrimitive?.contentOrNull,
            )
            else -> null
        }

        /** Flattens the tool `input` object to string values (skips nested arrays/objects). */
        private fun parseInput(element: JsonElement?): Map<String, String> {
            val obj = element as? JsonObject ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            for ((k, v) in obj) {
                (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it }
            }
            return out
        }
    }
}

/**
 * Per-tool result metadata (all optional). The renderer reads whichever apply:
 * edit → additions/deletions, grep → matches, bash → exit, todowrite → todos.
 * Mirrors iOS `ToolMetadata`.
 */
data class ToolMeta(
    val additions: Int? = null,
    val deletions: Int? = null,
    val matches: Int? = null,
    val exit: Int? = null,
    val todoTotal: Int? = null,
    val todoCompleted: Int? = null,
) {
    companion object {
        fun from(element: JsonElement?): ToolMeta? {
            val obj = element as? JsonObject ?: return null
            val filediff = obj["filediff"]?.jsonObject
            val todos = obj["todos"] as? JsonArray
            return ToolMeta(
                additions = filediff?.get("additions")?.jsonPrimitive?.intOrNull,
                deletions = filediff?.get("deletions")?.jsonPrimitive?.intOrNull,
                matches = obj["matches"]?.jsonPrimitive?.intOrNull,
                exit = obj["exit"]?.jsonPrimitive?.intOrNull,
                todoTotal = todos?.size,
                todoCompleted = todos?.count {
                    (it as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "completed"
                },
            )
        }
    }
}

/**
 * Parses the `GET /session/{id}/message` response — an array of
 * `{ info: {...}, parts: [...] }` — into [MessageWithParts]. Tolerates
 * malformed entries by skipping them. Shared by the REST seed path.
 */
object MessageParsing {
    fun parseMessageList(json: Json, body: String): List<MessageWithParts> {
        val root = json.parseToJsonElement(body)
        val array = (root as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        val out = mutableListOf<MessageWithParts>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val infoObj = obj["info"]?.jsonObject ?: continue
            val info = MessageInfo.from(infoObj) ?: continue
            val parts = (obj["parts"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(MessagePart::from) }
                ?.toMutableList()
                ?: mutableListOf()
            out.add(MessageWithParts(info, parts))
        }
        return out
    }
}
