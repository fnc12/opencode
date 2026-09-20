package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A pending permission request from the agent (the `permission[.v2].asked`
 * event and the `GET /permission` list). The agent wants to perform an action
 * (run a command, edit a file, fetch a URL…) and is blocked until the user
 * replies `once`, `always`, or `reject`. Mirrors iOS `PermissionRequest`.
 *
 * Speaks BOTH the v2 shape (`action`/`resources`) and the older v1 shape
 * (`permission`/`patterns`), so prompts surface against any server version —
 * an old server that only emits v1 no longer silently hangs the session.
 */
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String>,
) {
    /** A human-readable one-liner for the dock, e.g. "Run command: npm test". */
    val summary: String
        get() {
            val target = resources.joinToString(", ")
            return when (action) {
                "bash" -> if (target.isEmpty()) "Run a shell command" else "Run command: $target"
                "edit", "write" -> if (target.isEmpty()) "Modify a file" else "Modify file: $target"
                "webfetch" -> if (target.isEmpty()) "Fetch a URL" else "Fetch: $target"
                "websearch" -> "Search the web" + if (target.isEmpty()) "" else ": $target"
                "external_directory" ->
                    if (target.isEmpty()) "Access a directory outside the project" else "Access outside project: $target"
                else -> {
                    val verb = action.replaceFirstChar { it.uppercaseChar() }
                    if (target.isEmpty()) verb else "$verb: $target"
                }
            }
        }

    companion object {
        /** Parses a permission request object (event `properties` or a `/permission` list item). */
        fun from(obj: JsonObject): PermissionRequest? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionID = obj["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
            // v2 `action` ?? v1 `permission`; v2 `resources` ?? v1 `patterns`.
            val action = obj["action"]?.jsonPrimitive?.contentOrNull
                ?: obj["permission"]?.jsonPrimitive?.contentOrNull ?: ""
            val resourceArray = (obj["resources"] ?: obj["patterns"]) as? kotlinx.serialization.json.JsonArray
            val resources = resourceArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            return PermissionRequest(id, sessionID, action, resources)
        }

        /** Parses the `GET /permission` response (an array of request objects). */
        fun parseList(json: Json, body: String): List<PermissionRequest> {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
            val array = (root as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
            return array.mapNotNull { (it as? JsonObject)?.let(::from) }
        }
    }
}
