package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

/**
 * A slash command available in the session (`GET /command`) — built-in (e.g.
 * `init`, `review`) or project-defined. Mirrors iOS `CommandInfo`.
 */
@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
)
