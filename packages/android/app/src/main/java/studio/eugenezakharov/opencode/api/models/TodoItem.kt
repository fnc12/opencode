package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

/**
 * One task in a session's todo list (`GET /session/:id/todo`, `todo.updated`).
 * `status` is pending / in_progress / completed / cancelled. Mirrors iOS `TodoItem`.
 */
@Serializable
data class TodoItem(
    val content: String,
    val status: String,
    val priority: String? = null,
) {
    val done: Boolean get() = status == "completed" || status == "cancelled"
}
