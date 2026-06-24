package studio.eugenezakharov.opencode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.ServerEvent
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.PartContent

/**
 * End-to-end against a *real* OpenCode server, exercising the production path
 * (EventStream → ServerEvent → SessionStore): create a session, send a prompt,
 * and assert the assistant response streams in incrementally.
 *
 * Opt-in — skipped unless the server is provided via environment:
 *   OPENCODE_TEST_BASE   e.g. http://127.0.0.1:4096   (required)
 *   OPENCODE_TEST_DIR    a project worktree on that server (required)
 *   OPENCODE_TEST_PROVIDER / OPENCODE_TEST_MODEL  (default opencode / deepseek-v4-flash-free)
 *
 * Mirrors iOS `LiveSessionIntegrationTests`.
 */
class LiveSessionIntegrationTest {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun liveStreamingEndToEnd() = runBlocking {
        val base = System.getenv("OPENCODE_TEST_BASE")
        val dir = System.getenv("OPENCODE_TEST_DIR")
        assumeNotNull("set OPENCODE_TEST_BASE to run the live e2e test", base)
        assumeNotNull("set OPENCODE_TEST_DIR to run the live e2e test", dir)
        base!!; dir!!
        val provider = System.getenv("OPENCODE_TEST_PROVIDER") ?: "opencode"
        val model = System.getenv("OPENCODE_TEST_MODEL") ?: "deepseek-v4-flash-free"
        val encodedDir = java.net.URLEncoder.encode(dir, "UTF-8")

        // Create a fresh session.
        val created = postJson("$base/session?directory=$encodedDir", """{"title":"android live"}""")
        val sessionID = json.parseToJsonElement(created).let {
            (it as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"')
        }

        // Subscribe with the production stack.
        val server = ServerConnection(ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = base))
        val store = SessionStore()
        store.setInitial(emptyList())

        val scope = CoroutineScope(Dispatchers.IO + Job())
        scope.launch {
            val stream = server.eventStream() ?: return@launch
            runCatching {
                stream.frames().collect { data ->
                    ServerEvent.decode(json, data)?.let { store.apply(it, sessionID) }
                }
            }
        }
        delay(1_000) // let the stream attach

        // Generate activity.
        postJson(
            "$base/session/$sessionID/message?directory=$encodedDir",
            """{"parts":[{"type":"text","text":"Reply with a short haiku about databases. No preamble."}],"model":{"providerID":"$provider","modelID":"$model"}}""",
        )

        // Wait for the assistant text to assemble.
        var assistant = ""
        repeat(120) {
            delay(500)
            assistant = assistantText(store)
            if (assistant.isNotEmpty() && isComplete(store)) return@repeat
        }
        scope.cancel()

        assertFalse("assistant text should stream in over SSE", assistant.isEmpty())
        assertTrue(store.messages.any { it.info is MessageInfo.Assistant })
    }

    private fun assistantText(store: SessionStore): String {
        val sb = StringBuilder()
        for (m in store.messages) if (m.info is MessageInfo.Assistant) {
            for (p in m.parts) (p.content as? PartContent.Text)?.let { sb.append(it.text) }
        }
        return sb.toString()
    }

    private fun isComplete(store: SessionStore): Boolean =
        store.messages.any { (it.info as? MessageInfo.Assistant)?.completed != null }

    private fun postJson(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            check(response.isSuccessful) { "HTTP ${response.code}: $text" }
            return text
        }
    }
}
