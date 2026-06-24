package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A provider and its models, from `GET /config/providers`.
 *
 * SECURITY: we decode ONLY `id` / `name` and each model's `id` / `name`. The
 * provider object also carries a `key` field (an API secret) and other config —
 * those are never read or stored by the client. Mirrors iOS `ProviderInfo`.
 */
data class ProviderInfo(
    val id: String,
    val name: String?,
    val models: List<ModelInfo>,
)

data class ModelInfo(
    val id: String,
    val name: String?,
) {
    val displayName: String get() = name ?: id
}

object ProvidersParsing {
    /**
     * Parses the `/config/providers` response into [ProviderInfo]s, reading only
     * the whitelisted fields. Tolerates malformed entries by skipping them.
     */
    fun parse(json: Json, body: String): List<ProviderInfo> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val providers = root["providers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val out = mutableListOf<ProviderInfo>()
        for (element in providers) {
            val obj = element as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            // `models` is a map of modelID -> { id, name, ... }. Read id/name only.
            val modelsObj = obj["models"]?.jsonObject ?: JsonObject(emptyMap())
            val models = modelsObj.mapNotNull { (key, value) ->
                val mObj = value as? JsonObject
                val mid = mObj?.get("id")?.jsonPrimitive?.contentOrNull ?: key
                val mname = mObj?.get("name")?.jsonPrimitive?.contentOrNull
                ModelInfo(mid, mname)
            }.sortedBy { it.displayName.lowercase() }
            out.add(ProviderInfo(id, name, models))
        }
        return out
    }
}
