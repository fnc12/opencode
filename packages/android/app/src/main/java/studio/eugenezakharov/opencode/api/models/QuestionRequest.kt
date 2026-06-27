package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A pending question from the agent (`question.v2.asked` event and the `GET
 * /question` list). The agent is blocked until the user answers; each question
 * can be single- or multi-select, and the reply is an array of selected labels
 * per question. Mirrors iOS `QuestionRequest`.
 */
data class QuestionRequest(
    val id: String,
    val sessionID: String,
    val questions: List<QuestionItem>,
) {
    companion object {
        /** Parses a question request object (event `properties` or a `/question` list item). */
        fun from(obj: JsonObject): QuestionRequest? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionID = obj["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val questions = (obj["questions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(QuestionItem::from) }
                ?: emptyList()
            return QuestionRequest(id, sessionID, questions)
        }

        /** Parses the `GET /question` response (an array of request objects). */
        fun parseList(json: Json, body: String): List<QuestionRequest> {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
            val array = (root as? JsonArray) ?: return emptyList()
            return array.mapNotNull { (it as? JsonObject)?.let(::from) }
        }
    }
}

/**
 * One question within a [QuestionRequest]. `multiple` toggles single- vs
 * multi-select; `custom` allows a free-form answer (not yet surfaced in the UI).
 */
data class QuestionItem(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean? = null,
    val custom: Boolean? = null,
) {
    val allowsMultiple: Boolean get() = multiple == true

    /** Stable id (header+question are effectively unique per request). */
    val key: String get() = "$header|$question"

    companion object {
        fun from(obj: JsonObject): QuestionItem? {
            val question = obj["question"]?.jsonPrimitive?.contentOrNull ?: return null
            val header = obj["header"]?.jsonPrimitive?.contentOrNull ?: ""
            val options = (obj["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(QuestionOption::from) }
                ?: emptyList()
            val multiple = obj["multiple"]?.jsonPrimitive?.booleanOrNull
            val custom = obj["custom"]?.jsonPrimitive?.booleanOrNull
            return QuestionItem(question, header, options, multiple, custom)
        }
    }
}

/** A selectable option for a [QuestionItem]; `label` is what the reply sends. */
data class QuestionOption(
    val label: String,
    val description: String = "",
) {
    companion object {
        fun from(obj: JsonObject): QuestionOption? {
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: return null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            return QuestionOption(label, description)
        }
    }
}
