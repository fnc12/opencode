package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

/**
 * A selectable AI agent, from `GET /agent`. `mode` is subagent / primary / all;
 * only primary/all (non-hidden) agents are user-selectable. Mirrors iOS `AgentInfo`.
 */
@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
) {
    val selectable: Boolean get() = mode != "subagent" && !hidden
}
