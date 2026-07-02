package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

/**
 * One file's change in a session, from `GET /session/:id/diff`.
 * `patch` is a unified diff; `status` is added / deleted / modified.
 * Mirrors iOS `SessionFileDiff`.
 */
@Serializable
data class SessionFileDiff(
    val file: String? = null,
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null,
)
