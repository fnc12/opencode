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

@Serializable
data class Session(
    val id: String,
    val slug: String? = null,
    val projectID: String,
    val directory: String,
    val parentID: String? = null,
    val title: String = "",
    val version: String = "",
    val time: SessionTime,
    val summary: SessionSummary? = null,
    val share: SessionShare? = null,
)

@Serializable
data class SessionTime(
    val created: Double,
    val updated: Double,
    val compacting: Double? = null,
    val archived: Double? = null,
)

@Serializable
data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
)

@Serializable
data class SessionShare(
    val url: String,
)
