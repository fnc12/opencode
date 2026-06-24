package studio.eugenezakharov.opencode.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import studio.eugenezakharov.opencode.api.models.HealthResponse
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.Session
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * HTTP + SSE client for the OpenCode server. Mirrors the iOS `ServerConnection`.
 *
 * Endpoints: `/global/health`, `/project`, `/session?directory=…`,
 * `/session/{id}/message?directory=…`, and the live `GET /global/event` stream.
 *
 * Uses `java.util.Base64` (not `android.util.Base64`) so it stays usable from
 * plain JVM unit tests — the live e2e drives this exact class.
 */
class ServerConnection(
    var config: ConnectionConfig = ConnectionConfig(),
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Applies a pairing payload (from QR, deep link, or manual paste). */
    fun applyPairing(raw: String): Boolean {
        val parsed = ConnectionConfig.fromPairing(raw) ?: return false
        config = parsed
        return true
    }

    private fun basicAuthHeader(): String? =
        config.password?.takeIf { it.isNotEmpty() }?.let { pwd ->
            "Basic " + Base64.getEncoder().encodeToString("admin:$pwd".toByteArray())
        }

    suspend fun health(): HealthResponse = get("/global/health", HealthResponse.serializer())

    suspend fun projects(): List<Project> =
        get("/project", kotlinx.serialization.builtins.ListSerializer(Project.serializer()))

    suspend fun sessions(directory: String): List<Session> =
        get(
            "/session",
            kotlinx.serialization.builtins.ListSerializer(Session.serializer()),
            mapOf("directory" to directory),
        )

    /** Loads a session's message history (the seed for [SessionStore]). */
    suspend fun messages(directory: String, sessionID: String): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            val body = getRaw("/session/$sessionID/message", mapOf("directory" to directory))
            MessageParsing.parseMessageList(json, body)
        }

    /**
     * Builds an SSE reader for the global event stream (`GET /global/event`).
     * The instance stream (`/event`) only emits `server.connected`; all live
     * session activity is on the global bus. In relay mode this resolves to
     * `/t/{tunnelID}/global/event`. Events arrive for every session; callers
     * filter by `sessionID`. Returns null if the base URL is invalid.
     */
    fun eventStream(): EventStream? {
        val url = (config.baseURL + "/global/event").toHttpUrlOrNull()?.toString() ?: return null
        return EventStream(url, basicAuthHeader())
    }

    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        query: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        json.decodeFromString(serializer, getRaw(path, query))
    }

    private fun getRaw(path: String, query: Map<String, String>): String {
        val httpUrl = (config.baseURL + path).toHttpUrlOrNull() ?: throw ClientError.InvalidURL
        val urlBuilder = httpUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val requestBuilder = Request.Builder().url(urlBuilder.build())
        basicAuthHeader()?.let { requestBuilder.header("Authorization", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
            return body
        }
    }
}
