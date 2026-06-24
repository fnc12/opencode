package studio.eugenezakharov.opencode.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import studio.eugenezakharov.opencode.api.models.HealthResponse
import studio.eugenezakharov.opencode.api.models.Project
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the OpenCode server. Mirrors the iOS `ServerConnection`.
 *
 * Endpoints used: `/global/health`, `/project`. Live events (#15) will use
 * `GET /global/event` (SSE), parsed manually off the streaming response body.
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

    suspend fun health(): HealthResponse = get("/global/health", HealthResponse.serializer())

    suspend fun projects(): List<Project> =
        get("/project", kotlinx.serialization.builtins.ListSerializer(Project.serializer()))

    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        query: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val httpUrl = (config.baseURL + path).toHttpUrlOrNull()
            ?: throw ClientError.InvalidURL
        val urlBuilder = httpUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val requestBuilder = Request.Builder().url(urlBuilder.build())
        // The server's password (OPENCODE_SERVER_PASSWORD) is forwarded as Basic
        // auth; in relay mode the connector passes the header through.
        config.password?.takeIf { it.isNotEmpty() }?.let { pwd ->
            val cred = Base64.encodeToString("admin:$pwd".toByteArray(), Base64.NO_WRAP)
            requestBuilder.header("Authorization", "Basic $cred")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e("ServerConnection", "HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
            json.decodeFromString(serializer, body)
        }
    }
}
