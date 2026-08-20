package studio.eugenezakharov.opencode

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ServerConnection
import java.util.Base64

/**
 * The HTTP contract of [ServerConnection] against a fake server (MockWebServer) —
 * the "fake-server integration" layer of the test plan. No live tunnel, fully
 * deterministic. Covers the auth-header branches (the security-critical logic),
 * request shapes, response parsing, cursor paging, and error handling for the
 * endpoints the whole app depends on. Mirrors the surface iOS covers in its own
 * ServerConnection tests.
 */
class ServerConnectionTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun direct(password: String? = null) = ServerConnection(
        ConnectionConfig(
            mode = ConnectionMode.DIRECT,
            directURL = server.url("/").toString().trimEnd('/'),
            password = password,
        ),
    )

    private fun relay(token: String = "tok_abc", password: String? = null) = ServerConnection(
        ConnectionConfig(
            mode = ConnectionMode.RELAY,
            relayURL = server.url("/").toString().trimEnd('/'),
            tunnelID = "tun_1",
            token = token,
            password = password,
        ),
    )

    // --- auth headers (the security branches) --------------------------------

    @Test fun directWithoutPasswordSendsNoAuthHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1"}"""))
        direct().health()
        val req = server.takeRequest()
        assertNull("no password → no Authorization", req.getHeader("Authorization"))
        assertNull("direct mode → no tunnel token", req.getHeader("X-Tunnel-Token"))
    }

    @Test fun passwordProducesOpencodeBasicHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1"}"""))
        direct(password = "hunter2").health()
        val req = server.takeRequest()
        val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:hunter2".toByteArray())
        // The server's Basic username is "opencode", not "admin" — a past bug 401'd everything.
        assertEquals(expected, req.getHeader("Authorization"))
    }

    @Test fun relayModeSendsTunnelTokenHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1"}"""))
        relay(token = "tok_xyz").health()
        val req = server.takeRequest()
        assertEquals("tok_xyz", req.getHeader("X-Tunnel-Token"))
    }

    @Test fun directModeNeverSendsTunnelToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1"}"""))
        direct().health()
        assertNull(server.takeRequest().getHeader("X-Tunnel-Token"))
    }

    @Test fun relayBaseUrlIncludesTunnelPath() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        relay().projects()
        // Relay base = relayURL/t/{tunnelID}, so the proxied path is prefixed.
        assertTrue(server.takeRequest().path!!.startsWith("/t/tun_1/project"))
    }

    // --- read endpoints: request shape + parsing -----------------------------

    @Test fun healthParsesResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.2.3"}"""))
        val h = direct().health()
        assertEquals("/global/health", server.takeRequest().path)
        assertEquals(true, h.healthy)
        assertEquals("1.2.3", h.version)
    }

    @Test fun projectsParsesList() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"p1","worktree":"/w","time":{"created":1.0,"updated":2.0}}]""",
            ),
        )
        val p = direct().projects()
        assertEquals("/project", server.takeRequest().path)
        assertEquals(1, p.size)
        assertEquals("p1", p.first().id)
    }

    @Test fun sessionsPassesDirectoryQuery() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        direct().sessions("/srv/work")
        assertEquals("/session?directory=%2Fsrv%2Fwork", server.takeRequest().path)
    }

    @Test fun getSessionByIdNeedsNoDirectory() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"ses_9","projectID":"p","directory":"/w","time":{"created":1.0,"updated":2.0}}""",
            ),
        )
        val s = direct().getSession("ses_9")
        assertEquals("/session/ses_9", server.takeRequest().path)
        assertEquals("ses_9", s.id)
    }

    @Test fun messagesPageReadsNextCursorHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("X-Next-Cursor", "cur_42"))
        val page = direct().messagesPage("/w", "ses_1", limit = 5)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("limit=5"))
        assertEquals("cur_42", page.nextCursor)
    }

    @Test fun messagesPageEmptyCursorBecomesNull() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("X-Next-Cursor", ""))
        // Reaching the first message: an empty cursor header must map to null (no older page).
        assertNull(direct().messagesPage("/w", "ses_1", limit = 5).nextCursor)
    }

    @Test fun messagesPageForwardsBeforeCursor() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        direct().messagesPage("/w", "ses_1", limit = 20, before = "cur_7")
        assertTrue(server.takeRequest().path!!.contains("before=cur_7"))
    }

    // --- write endpoints -----------------------------------------------------

    @Test fun abortPostsToAbort() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().abort("/w", "ses_1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/session/ses_1/abort"))
    }

    @Test fun replyPermissionPostsReply() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().replyPermission("/w", "per_1", "once")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("per_1"))
        assertTrue(req.body.readUtf8().contains("once"))
    }

    @Test fun rejectQuestionPosts() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().rejectQuestion("/w", "que_1")
        assertEquals("POST", server.takeRequest().method)
    }

    // --- error handling ------------------------------------------------------

    @Test(expected = Exception::class)
    fun unauthorizedThrows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        direct().projects()
        Unit
    }

    @Test(expected = Exception::class)
    fun serverErrorThrows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        direct().health()
        Unit
    }

    // Each raw helper's non-2xx branch, via a method that uses it.

    @Test(expected = Exception::class)
    fun postRawErrorThrows() = runBlocking { // abort() -> postRaw
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        direct().abort("/w", "ses_1"); Unit
    }

    @Test(expected = Exception::class)
    fun sendRawErrorThrows() = runBlocking { // deleteSession() -> sendRaw
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        direct().deleteSession("/w", "ses_1"); Unit
    }

    @Test(expected = Exception::class)
    fun getRawWithHeaderErrorThrows() = runBlocking { // messagesPage() -> getRawWithHeader
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        direct().messagesPage("/w", "ses_1", 5); Unit
    }

    @Test(expected = Exception::class)
    fun postRawForResultErrorThrows() = runBlocking { // createSession() -> postRawForResult
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        direct().createSession("/w"); Unit
    }

    @Test(expected = Exception::class)
    fun sendRawForResultErrorThrows() = runBlocking { // unshareSession() -> sendRawForResult
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        direct().unshareSession("/w", "ses_1"); Unit
    }

    // --- push registration (relay-only branch) -------------------------------

    @Test fun registerPushTokenSkippedInDirectMode() = runBlocking {
        // Direct mode has no relay/tunnel → must not hit the network at all.
        assertTrue(!direct().registerPushToken("dtok"))
        assertEquals(0, server.requestCount)
    }

    @Test fun registerPushTokenPostsToRelayDevices() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val ok = relay().registerPushToken("dtok", provider = "fcm")
        val req = server.takeRequest()
        assertTrue(ok)
        assertTrue(req.path!!.endsWith("/api/devices"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("tun_1"))
        assertTrue(body.contains("dtok"))
        assertTrue(body.contains("fcm"))
    }

    // --- remaining endpoints: CRUD, parse, share, revert ---------------------

    @Test fun sessionTodosParsesList() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"content":"do it","status":"pending","priority":"high"}]"""))
        val todos = direct().sessionTodos("/w", "ses_1")
        assertTrue(server.takeRequest().path!!.startsWith("/session/ses_1/todo"))
        assertEquals(1, todos.size)
    }

    @Test fun listDirectoryPassesDirectoryAndDotPath() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"name":"a","type":"file","path":"/w/a","absolute":"/w/a"}]"""))
        direct().listDirectory("/w")
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/file?"))
        assertTrue(path.contains("path=."))
    }

    @Test fun readFileReturnsContent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":"hello world"}"""))
        val text = direct().readFile("/w", "a.txt")
        assertTrue(server.takeRequest().path!!.startsWith("/file/content"))
        assertEquals("hello world", text)
    }

    @Test fun renameSessionPatchesTitle() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().renameSession("/w", "ses_1", "New name")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertTrue(req.path!!.startsWith("/session/ses_1"))
        assertTrue(req.body.readUtf8().contains("New name"))
    }

    @Test fun deleteSessionUsesDeleteVerb() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().deleteSession("/w", "ses_1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.startsWith("/session/ses_1"))
    }

    @Test fun shareSessionReturnsUpdatedSession() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"ses_1","projectID":"p","directory":"/w","time":{"created":1.0,"updated":2.0},
                    "share":{"url":"https://share/x"}}""",
            ),
        )
        val s = direct().shareSession("/w", "ses_1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/session/ses_1/share"))
        assertEquals("ses_1", s.id)
    }

    @Test fun unshareSessionDeletesShare() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"ses_1","projectID":"p","directory":"/w","time":{"created":1.0,"updated":2.0}}""",
            ),
        )
        direct().unshareSession("/w", "ses_1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.contains("/share"))
    }

    @Test fun revertSessionSendsMessageId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().revertSession("/w", "ses_1", "msg_9")
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/revert"))
        assertTrue(req.body.readUtf8().contains("msg_9"))
    }

    @Test fun replyQuestionSendsNestedAnswersArray() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().replyQuestion("/w", "que_1", listOf(listOf("A"), listOf("B", "C")))
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("que_1"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("answers"))
        assertTrue(body.contains("A") && body.contains("B") && body.contains("C"))
    }

    @Test fun runShellExtractsToolOutput() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"parts":[{"type":"tool","state":{"output":"total 0"}}]}""",
            ),
        )
        val out = direct().runShell("/w", "ses_1", "ls")
        assertTrue(server.takeRequest().path!!.contains("/shell"))
        assertEquals("total 0", out)
    }

    @Test fun runShellFallsBackWhenNoToolOutput() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"parts":[{"type":"text","text":"hi"}]}"""))
        assertEquals("(no output)", direct().runShell("/w", "ses_1", "ls"))
    }

    @Test fun sessionDiffParsesList() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        direct().sessionDiff("/w", "ses_1")
        assertTrue(server.takeRequest().path!!.startsWith("/session/ses_1/diff"))
    }

    @Test fun agentsHitsAgentEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        direct().agents()
        assertEquals("/agent", server.takeRequest().path)
    }

    @Test fun commandsPassesDirectory() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        direct().commands("/w")
        assertTrue(server.takeRequest().path!!.startsWith("/command?directory="))
    }

    @Test fun messagesFullHistory() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        direct().messages("/w", "ses_1")
        assertTrue(server.takeRequest().path!!.startsWith("/session/ses_1/message"))
    }

    @Test fun providerAuthMethods() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        direct().providerAuthMethods()
        assertEquals("/provider/auth", server.takeRequest().path)
    }

    @Test fun setProviderKeyPuts() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().setProviderKey("openai", "sk-123")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.contains("openai"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("api") && body.contains("sk-123"))
    }

    @Test fun providersParsesConfig() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"providers":[]}"""))
        direct().providers()
        assertEquals("/config/providers", server.takeRequest().path)
    }

    @Test fun runCommandPosts() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().runCommand("/w", "ses_1", "init")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("/command"))
        assertTrue(req.body.readUtf8().contains("init"))
    }

    @Test fun sendPromptPostsPromptAndModel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        direct().sendPrompt("/w", "ses_1", "hello there", "zai", "glm-5.2", "build")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/session/ses_1/message"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("hello there"))
        assertTrue(body.contains("glm-5.2"))
    }

    // --- InvalidURL guards (a non-http base URL → toHttpUrlOrNull() is null) ---

    private fun malformed() = ServerConnection(
        ConnectionConfig(mode = ConnectionMode.DIRECT, directURL = "not a url"),
    )

    @Test(expected = Exception::class)
    fun getRawInvalidUrlThrows() = runBlocking { malformed().projects(); Unit }
    @Test(expected = Exception::class)
    fun getRawWithHeaderInvalidUrlThrows() = runBlocking { malformed().messagesPage("/w", "s", 5); Unit }
    @Test(expected = Exception::class)
    fun postRawInvalidUrlThrows() = runBlocking { malformed().abort("/w", "s"); Unit }
    @Test(expected = Exception::class)
    fun postRawForResultInvalidUrlThrows() = runBlocking { malformed().createSession("/w"); Unit }
    @Test(expected = Exception::class)
    fun sendRawInvalidUrlThrows() = runBlocking { malformed().deleteSession("/w", "s"); Unit }
    @Test(expected = Exception::class)
    fun sendRawForResultInvalidUrlThrows() = runBlocking { malformed().unshareSession("/w", "s"); Unit }

    @Test fun eventStreamNullOnInvalidUrl() {
        assertNull(malformed().eventStream())
    }

    @Test fun sendPromptSerializesAttachments() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val image = studio.eugenezakharov.opencode.api.models.PromptAttachment(
            mime = "image/png", filename = "shot.png", url = "data:image/png;base64,aGk=",
        )
        val repoFile = studio.eugenezakharov.opencode.api.models.PromptAttachment(
            mime = "text/plain", filename = "main.kt", url = "file:///w/main.kt",
            sourcePath = "/w/main.kt", sourceContent = "fun main() {}",
        )
        direct().sendPrompt("/w", "ses_1", "look at these", "zai", "glm-5.2", "build",
            attachments = listOf(image, repoFile))
        val body = server.takeRequest().body.readUtf8()
        // Both attachments are serialized as file parts before the text part…
        assertTrue(body.contains("shot.png"))
        assertTrue(body.contains("main.kt"))
        assertTrue(body.contains("look at these"))
        // …and the repo file carries an inline source block (path + text).
        assertTrue(body.contains("\"source\""))
        assertTrue(body.contains("fun main() {}"))
    }
}
