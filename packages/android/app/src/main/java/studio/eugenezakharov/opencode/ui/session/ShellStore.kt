package studio.eugenezakharov.opencode.ui.session

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Keeps each session's shell history alive across opening/closing the terminal
 * dialog (and navigating away and back), so Close no longer discards what you
 * ran. In-memory for the process lifetime, keyed by session id. Compose reads it
 * reactively. Mirrors the iOS ShellStore.
 *
 * NOTE: still the one-shot `/session/:id/shell` model (each command independent).
 * Real persistent, interactive, multi-tab terminals are a follow-up on the PTY API.
 */
object ShellStore {
    private val histories = mutableStateMapOf<String, SnapshotStateList<Pair<String, String>>>()

    /** A session's command+output history, created on first access. */
    fun history(sessionId: String): SnapshotStateList<Pair<String, String>> =
        histories.getOrPut(sessionId) { mutableStateListOf() }

    fun clear(sessionId: String) {
        histories[sessionId]?.clear()
    }
}
