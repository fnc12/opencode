package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

/**
 * An entry from `GET /file` (directory listing) — used by the folder browser.
 * Mirrors the iOS `FileEntry`.
 */
@Serializable
data class FileEntry(
    val name: String,
    val path: String = "",
    val absolute: String,
    val type: String,          // "directory" | "file"
    val ignored: Boolean? = null,
) {
    val isDirectory: Boolean get() = type == "directory"
}
