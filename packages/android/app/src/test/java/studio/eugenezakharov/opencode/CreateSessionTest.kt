package studio.eugenezakharov.opencode

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection

/**
 * `ServerConnection.createSession` request shape and response parsing, driven
 * through a real OkHttp request via MockWebServer. Mirrors the iOS createSession
 * coverage: `POST /session?directory=…` with a `{}` (or `{"title":…}`) body, and
 * the returned Session decoded back. The composer's "open folder" / "new session"
 * flows depend on this exact contract.
 */
class CreateSessionTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun connection(): ServerConnection = ServerConnection(
        ConnectionConfig(
            mode = ConnectionMode.DIRECT,
            directURL = server.url("/").toString().trimEnd('/'),
        ),
    )

    private val sessionResponse = """
        {"id":"ses_new","projectID":"prj_1","directory":"/srv/work",
         "title":"","version":"1.0.0","time":{"created":1.0,"updated":2.0}}
    """.trimIndent()

    @Test
    fun postsToSessionWithDirectoryAndEmptyBody() = runBlocking {
        server.enqueue(MockResponse().setBody(sessionResponse))

        val session = connection().createSession("/srv/work")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        // OkHttp percent-encodes the path separators in the query value.
        assertEquals("/session?directory=%2Fsrv%2Fwork", request.path)
        // No title → empty JSON object body.
        assertEquals("{}", request.body.readUtf8())
        assertEquals("ses_new", session.id)
        assertEquals("/srv/work", session.directory)
    }

    @Test
    fun sendsTitleWhenProvided() = runBlocking {
        server.enqueue(MockResponse().setBody(sessionResponse))

        connection().createSession("/srv/work", title = "Hello")

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Hello", body["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodesDirectoryQuery() = runBlocking {
        server.enqueue(MockResponse().setBody(sessionResponse))

        connection().createSession("/mnt/data/sources/sqlite orm")

        val request = server.takeRequest()
        assertTrue("directory should be URL-encoded: ${request.path}",
            request.path!!.startsWith("/session?directory="))
        assertTrue(request.path!!.contains("sqlite%20orm"))
    }
}
