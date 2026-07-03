package studio.eugenezakharov.opencode.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import studio.eugenezakharov.opencode.api.models.FileEntry
import studio.eugenezakharov.opencode.api.models.HealthResponse
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PermissionRequest
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.QuestionRequest
import studio.eugenezakharov.opencode.api.models.ProviderInfo
import studio.eugenezakharov.opencode.api.models.ProvidersParsing
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionFileDiff
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
            // The OpenCode server's default username is "opencode" (not "admin");
            // the connector forwards this Basic header through in relay mode.
            "Basic " + Base64.getEncoder().encodeToString("opencode:$pwd".toByteArray())
        }

    /** In relay mode, the per-tunnel token the relay checks (`X-Tunnel-Token`); null in direct mode. */
    private fun tunnelToken(): String? =
        config.token.takeIf { config.mode == ConnectionMode.RELAY && it.isNotEmpty() }

    /** Applies the auth headers every request shares: Basic password + relay X-Tunnel-Token. */
    private fun applyAuth(rb: Request.Builder) {
        basicAuthHeader()?.let { rb.header("Authorization", it) }
        tunnelToken()?.let { rb.header("X-Tunnel-Token", it) }
    }

    /**
     * Registers this device's push token with the relay so it can wake the app
     * when a session goes idle (`POST <relay-root>/api/devices`). Relay mode only.
     */
    suspend fun registerPushToken(token: String, provider: String = "fcm"): Boolean =
        withContext(Dispatchers.IO) {
            if (config.mode != ConnectionMode.RELAY || config.tunnelID.isEmpty()) return@withContext false
            val url = config.relayURL.trimmedSlashes() + "/api/devices"
            val body = buildJsonObject {
                put("tunnelId", config.tunnelID)
                put("provider", provider)
                put("token", token)
            }.toString()
            val request = Request.Builder().url(url)
                .post(body.toRequestBody("application/json".toMediaType())).build()
            runCatching { client.newCall(request).execute().use { it.isSuccessful } }
                .getOrDefault(false)
        }

    suspend fun health(): HealthResponse = get("/global/health", HealthResponse.serializer())

    suspend fun projects(): List<Project> =
        get("/project", kotlinx.serialization.builtins.ListSerializer(Project.serializer()))

    /**
     * Lists the contents of a directory on the server (`GET /file?directory=…&path=.`).
     * Works for any path the server can read — powers the folder browser. Mirrors
     * iOS `listDirectory`.
     */
    suspend fun listDirectory(path: String): List<FileEntry> =
        get(
            "/file",
            kotlinx.serialization.builtins.ListSerializer(FileEntry.serializer()),
            mapOf("directory" to path, "path" to "."),
        )

    suspend fun sessions(directory: String): List<Session> =
        get(
            "/session",
            kotlinx.serialization.builtins.ListSerializer(Session.serializer()),
            mapOf("directory" to directory),
        )

    /**
     * Creates a new session in a directory and returns it (`POST /session?directory=…`).
     * Works for any folder the server can see — including on a fresh server with no
     * projects yet — which is how you start work on a fresh server. An optional title
     * is sent as `{"title":…}`; otherwise the body is `{}`. Mirrors iOS `createSession`.
     */
    suspend fun createSession(directory: String, title: String? = null): Session =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                if (!title.isNullOrEmpty()) put("title", title)
            }
            val response = postRawForResult("/session", mapOf("directory" to directory), body.toString())
            json.decodeFromString(Session.serializer(), response)
        }

    /** Loads a session's message history (the seed for [SessionStore]). */
    suspend fun messages(directory: String, sessionID: String): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            val body = getRaw("/session/$sessionID/message", mapOf("directory" to directory))
            MessageParsing.parseMessageList(json, body)
        }

    /** The session's current todo list (`GET /session/:id/todo`). Mirrors iOS. */
    suspend fun sessionTodos(directory: String, sessionID: String): List<studio.eugenezakharov.opencode.api.models.TodoItem> =
        get(
            "/session/$sessionID/todo",
            kotlinx.serialization.builtins.ListSerializer(
                studio.eugenezakharov.opencode.api.models.TodoItem.serializer(),
            ),
            mapOf("directory" to directory),
        )

    /** Reads a file's text content (`GET /file/content`) to attach as prompt context. */
    suspend fun readFile(directory: String, path: String): String = withContext(Dispatchers.IO) {
        val obj = get(
            "/file/content",
            kotlinx.serialization.json.JsonObject.serializer(),
            mapOf("directory" to directory, "path" to path),
        )
        obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    /** Auth methods available per provider (`GET /provider/auth`). Mirrors iOS. */
    suspend fun providerAuthMethods(): Map<String, List<studio.eugenezakharov.opencode.api.models.ProviderAuthMethod>> =
        get(
            "/provider/auth",
            kotlinx.serialization.builtins.MapSerializer(
                String.serializer(),
                kotlinx.serialization.builtins.ListSerializer(
                    studio.eugenezakharov.opencode.api.models.ProviderAuthMethod.serializer(),
                ),
            ),
            emptyMap(),
        )

    /** Set an API key for a provider (`PUT /auth/:id`). */
    suspend fun setProviderKey(providerID: String, key: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("type", "api")
            put("key", key)
        }
        sendRaw("PUT", "/auth/$providerID", emptyMap(), body.toString())
    }

    /** Remove a provider's stored credentials (`DELETE /auth/:id`). */
    suspend fun removeProviderAuth(providerID: String) = withContext(Dispatchers.IO) {
        sendRaw("DELETE", "/auth/$providerID", emptyMap(), null)
    }

    /** Slash commands available for this server (`GET /command`). Mirrors iOS. */
    suspend fun commands(directory: String): List<studio.eugenezakharov.opencode.api.models.CommandInfo> =
        get(
            "/command",
            kotlinx.serialization.builtins.ListSerializer(
                studio.eugenezakharov.opencode.api.models.CommandInfo.serializer(),
            ),
            mapOf("directory" to directory),
        )

    /** Run a slash command in the session (`POST /session/:id/command`). */
    suspend fun runCommand(directory: String, sessionID: String, command: String, arguments: String = "") =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("command", command)
                put("arguments", arguments)
            }
            postRaw("/session/$sessionID/command", mapOf("directory" to directory), body.toString())
        }

    /** Run a shell command in the session (`POST /session/:id/shell`) and return its output. */
    suspend fun runShell(directory: String, sessionID: String, command: String, agent: String = "build"): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("agent", agent)
                put("command", command)
            }
            val raw = postRawForResult("/session/$sessionID/shell", mapOf("directory" to directory), body.toString())
            val obj = json.parseToJsonElement(raw).jsonObject
            val parts = obj["parts"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return@withContext "(no output)"
            for (p in parts) {
                val po = p.jsonObject
                if (po["type"]?.jsonPrimitive?.contentOrNull == "tool") {
                    val out = po["state"]?.jsonObject?.get("output")?.jsonPrimitive?.contentOrNull
                    if (!out.isNullOrEmpty()) return@withContext out
                }
            }
            "(no output)"
        }

    /** The aggregate file changes for a session (`GET /session/:id/diff`). Mirrors iOS. */
    suspend fun sessionDiff(directory: String, sessionID: String): List<SessionFileDiff> =
        get(
            "/session/$sessionID/diff",
            kotlinx.serialization.builtins.ListSerializer(SessionFileDiff.serializer()),
            mapOf("directory" to directory),
        )

    /** Lists providers and their models for the model picker (`GET /config/providers`). */
    suspend fun providers(): List<ProviderInfo> = withContext(Dispatchers.IO) {
        ProvidersParsing.parse(json, getRaw("/config/providers", emptyMap()))
    }

    /** Lists available agents (`GET /agent`) — build / plan / custom. Mirrors iOS. */
    suspend fun agents(): List<studio.eugenezakharov.opencode.api.models.AgentInfo> =
        get(
            "/agent",
            kotlinx.serialization.builtins.ListSerializer(
                studio.eugenezakharov.opencode.api.models.AgentInfo.serializer(),
            ),
        )

    /** Lists pending permission requests (`GET /permission?directory=…`). */
    suspend fun permissions(directory: String): List<PermissionRequest> = withContext(Dispatchers.IO) {
        PermissionRequest.parseList(json, getRaw("/permission", mapOf("directory" to directory)))
    }

    /** Answers a permission request: `once`, `always`, or `reject`. */
    suspend fun replyPermission(directory: String, requestID: String, reply: String) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("reply", reply) }
            postRaw("/permission/$requestID/reply", mapOf("directory" to directory), body.toString())
        }

    /** Lists pending questions (`GET /question?directory=…`). */
    suspend fun questions(directory: String): List<QuestionRequest> = withContext(Dispatchers.IO) {
        QuestionRequest.parseList(json, getRaw("/question", mapOf("directory" to directory)))
    }

    /** Answers a question — one array of selected labels per question (`{"answers": [[…], …]}`). */
    suspend fun replyQuestion(directory: String, requestID: String, answers: List<List<String>>) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonArray("answers") {
                    answers.forEach { perQuestion ->
                        addJsonArray { perQuestion.forEach { add(it) } }
                    }
                }
            }
            postRaw("/question/$requestID/reply", mapOf("directory" to directory), body.toString())
        }

    /** Rejects a question (dismiss without answering). */
    suspend fun rejectQuestion(directory: String, requestID: String) =
        withContext(Dispatchers.IO) {
            postRaw("/question/$requestID/reject", mapOf("directory" to directory), "{}")
        }

    /**
     * Aborts the in-flight generation for a session
     * (`POST /session/{id}/abort?directory=…`, no body). Mirrors iOS `abort`.
     */
    suspend fun abort(directory: String, sessionID: String) = withContext(Dispatchers.IO) {
        postRaw("/session/$sessionID/abort", mapOf("directory" to directory), "{}")
    }

    /** Reverts the session to before [messageID] (undoes it + everything after, incl. file changes). */
    suspend fun revertSession(directory: String, sessionID: String, messageID: String) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("messageID", messageID) }
            postRaw("/session/$sessionID/revert", mapOf("directory" to directory), body.toString())
        }

    /** Restores all reverted messages. `POST /session/:id/unrevert`. */
    suspend fun unrevertSession(directory: String, sessionID: String) = withContext(Dispatchers.IO) {
        postRaw("/session/$sessionID/unrevert", mapOf("directory" to directory), "{}")
    }

    /** Renames a session (sets its title). `PATCH /session/{id}`. Mirrors iOS. */
    suspend fun renameSession(directory: String, sessionID: String, title: String) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("title", title) }
            sendRaw("PATCH", "/session/$sessionID", mapOf("directory" to directory), body.toString())
        }

    /** Deletes a session. `DELETE /session/{id}`. Mirrors iOS. */
    suspend fun deleteSession(directory: String, sessionID: String) =
        withContext(Dispatchers.IO) {
            sendRaw("DELETE", "/session/$sessionID", mapOf("directory" to directory), null)
        }

    /** Creates a public share link (`POST /session/{id}/share`); returns the updated session. */
    suspend fun shareSession(directory: String, sessionID: String): Session =
        withContext(Dispatchers.IO) {
            val body = postRawForResult("/session/$sessionID/share", mapOf("directory" to directory), "{}")
            json.decodeFromString(Session.serializer(), body)
        }

    /** Stops sharing (`DELETE /session/{id}/share`); returns the updated session. */
    suspend fun unshareSession(directory: String, sessionID: String): Session =
        withContext(Dispatchers.IO) {
            val body = sendRawForResult("DELETE", "/session/$sessionID/share", mapOf("directory" to directory), null)
            json.decodeFromString(Session.serializer(), body)
        }

    /**
     * Sends a text prompt to a session. The assistant's reply streams back over
     * the event stream, so the caller doesn't need the response body. A model is
     * required — the server has no default.
     */
    suspend fun sendPrompt(
        directory: String,
        sessionID: String,
        text: String,
        providerID: String,
        modelID: String,
        agent: String? = null,
        attachments: List<studio.eugenezakharov.opencode.api.models.PromptAttachment> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonArray("parts") {
                attachments.forEach { att ->
                    addJsonObject {
                        put("type", "file")
                        put("mime", att.mime)
                        put("filename", att.filename)
                        put("url", att.url)
                        if (att.sourcePath != null && att.sourceContent != null) {
                            putJsonObject("source") {
                                put("type", "file")
                                put("path", att.sourcePath)
                                putJsonObject("text") {
                                    put("value", att.sourceContent)
                                    put("start", 0)
                                    put("end", att.sourceContent.length)
                                }
                            }
                        }
                    }
                }
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            putJsonObject("model") {
                put("providerID", providerID)
                put("modelID", modelID)
            }
            if (!agent.isNullOrEmpty()) put("agent", agent)
        }
        postRaw("/session/$sessionID/message", mapOf("directory" to directory), body.toString())
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
        return EventStream(url, basicAuthHeader(), tunnelToken())
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
        applyAuth(requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
            return body
        }
    }

    /** Like [postRaw] but returns the response body — used by endpoints that reply with JSON. */
    private fun postRawForResult(path: String, query: Map<String, String>, jsonBody: String): String {
        val httpUrl = (config.baseURL + path).toHttpUrlOrNull() ?: throw ClientError.InvalidURL
        val urlBuilder = httpUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
        applyAuth(requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
            return body
        }
    }

    private fun postRaw(path: String, query: Map<String, String>, jsonBody: String) {
        val httpUrl = (config.baseURL + path).toHttpUrlOrNull() ?: throw ClientError.InvalidURL
        val urlBuilder = httpUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
        applyAuth(requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                System.err.println("HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
        }
    }

    /** Like [sendRaw] but returns the response body (for verbs that reply with JSON). */
    private fun sendRawForResult(method: String, path: String, query: Map<String, String>, jsonBody: String?): String {
        val httpUrl = (config.baseURL + path).toHttpUrlOrNull() ?: throw ClientError.InvalidURL
        val urlBuilder = httpUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val reqBody = jsonBody?.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(urlBuilder.build()).method(method, reqBody)
        applyAuth(requestBuilder)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
            return body
        }
    }

    /** Generic request for verbs beyond GET/POST (PATCH/DELETE); body optional. */
    private fun sendRaw(method: String, path: String, query: Map<String, String>, jsonBody: String?) {
        val httpUrl = (config.baseURL + path).toHttpUrlOrNull() ?: throw ClientError.InvalidURL
        val urlBuilder = httpUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val reqBody = jsonBody?.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(urlBuilder.build()).method(method, reqBody)
        applyAuth(requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                System.err.println("HTTP ${response.code}: ${urlBuilder.build()}\n$body")
                throw ClientError.Http(response.code)
            }
        }
    }
}
