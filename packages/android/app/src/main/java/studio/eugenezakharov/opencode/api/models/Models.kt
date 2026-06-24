package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val healthy: Boolean,
    val version: String,
)

@Serializable
data class Project(
    val id: String,
    val worktree: String,
    val vcs: String? = null,
    val name: String? = null,
    val time: ProjectTime,
    val sandboxes: List<String> = emptyList(),
)

@Serializable
data class ProjectTime(
    val created: Double,
    val updated: Double,
    val initialized: Double? = null,
)
