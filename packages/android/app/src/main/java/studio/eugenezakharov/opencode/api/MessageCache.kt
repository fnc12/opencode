package studio.eugenezakharov.opencode.api

import android.content.Context
import kotlinx.serialization.json.Json
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import java.io.File

/**
 * A tiny on-disk cache of a session's newest message page, so reopening a
 * session paints instantly from disk while the network refresh runs in parallel.
 * Stores the raw server JSON (the `[MessageWithParts]` array) under the app cache
 * dir, keyed by session id — best-effort and evictable (the OS may purge
 * cacheDir), never fatal on read or write failure. Mirrors iOS `MessageCache`.
 */
class MessageCache(dir: File) {
    private val dir: File = dir.apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    constructor(context: Context) : this(File(context.cacheDir, "message-cache"))

    // Session ids are `ses_<hex>` (filename-safe), but sanitize defensively.
    private fun file(sessionID: String) = File(dir, sessionID.replace('/', '_') + ".json")

    /** The last-cached newest page for a session, or null on a miss / parse error. */
    fun load(sessionID: String): List<MessageWithParts>? = runCatching {
        val f = file(sessionID)
        if (!f.exists()) null else MessageParsing.parseMessageList(json, f.readText())
    }.getOrNull()

    /**
     * Persist the raw newest-page response body (the server's JSON array), which
     * [load] parses back with the production model path. Best-effort — a write
     * failure just means the next reopen falls back to a cold load.
     */
    fun save(sessionID: String, raw: String) {
        runCatching { file(sessionID).writeText(raw) }
    }
}
