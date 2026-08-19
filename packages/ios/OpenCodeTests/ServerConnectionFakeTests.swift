import XCTest
@testable import OpenCode

/// A `URLProtocol` that answers every request from a queued canned response and
/// records the requests it saw — an in-process fake server, so the whole HTTP
/// surface of `ServerConnection` runs offline and deterministically (the
/// fake-server layer of the test plan, mirroring Android's MockWebServer).
///
/// Note: URLProtocol does not expose `httpBody` for URLSession requests (the body
/// travels as a stream), so these tests assert on URL / method / headers /
/// response parsing / errors. Request-body shapes are covered on the Android side.
final class StubURLProtocol: URLProtocol {
    struct Response { let status: Int; let headers: [String: String]; let body: Data }

    /// FIFO queue of responses to hand out, and the requests seen, in order.
    /// Tests drive this serially; the stub touches it on the URL-loading thread.
    nonisolated(unsafe) static var queue: [Response] = []
    nonisolated(unsafe) static var seen: [URLRequest] = []

    static func reset() { queue = []; seen = [] }
    static func enqueue(_ status: Int = 200, headers: [String: String] = [:], body: String = "") {
        queue.append(Response(status: status, headers: headers, body: Data(body.utf8)))
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.seen.append(request)
        let r = Self.queue.isEmpty ? Response(status: 200, headers: [:], body: Data("{}".utf8))
                                   : Self.queue.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: r.status,
                                       httpVersion: "HTTP/1.1", headerFields: r.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: r.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    /// A URLSession whose only protocol is this stub.
    static func session() -> URLSession {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: cfg)
    }
}

@MainActor
final class ServerConnectionFakeTests: XCTestCase {
    override func setUp() { super.setUp(); StubURLProtocol.reset() }

    private func direct(password: String? = nil) -> ServerConnection {
        var cfg = ConnectionConfig()
        cfg.mode = .direct
        cfg.directURL = "http://stub.local"
        cfg.password = password
        return ServerConnection(config: cfg, session: StubURLProtocol.session())
    }

    private func relay(token: String = "tok_abc", password: String? = nil) -> ServerConnection {
        var cfg = ConnectionConfig()
        cfg.mode = .relay
        cfg.relayURL = "http://stub.local"
        cfg.tunnelID = "tun_1"
        cfg.token = token
        cfg.password = password
        return ServerConnection(config: cfg, session: StubURLProtocol.session())
    }

    private var lastRequest: URLRequest { StubURLProtocol.seen.last! }

    // --- auth headers (the security branches) --------------------------------

    func testDirectWithoutPasswordSendsNoAuth() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().projects()
        XCTAssertNil(lastRequest.value(forHTTPHeaderField: "Authorization"))
        XCTAssertNil(lastRequest.value(forHTTPHeaderField: "X-Tunnel-Token"))
    }

    func testPasswordProducesOpencodeBasicHeader() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct(password: "hunter2").projects()
        let expected = "Basic " + Data("opencode:hunter2".utf8).base64EncodedString()
        XCTAssertEqual(lastRequest.value(forHTTPHeaderField: "Authorization"), expected)
    }

    func testRelayModeSendsTunnelToken() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await relay(token: "tok_xyz").projects()
        XCTAssertEqual(lastRequest.value(forHTTPHeaderField: "X-Tunnel-Token"), "tok_xyz")
    }

    func testDirectModeNeverSendsTunnelToken() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().projects()
        XCTAssertNil(lastRequest.value(forHTTPHeaderField: "X-Tunnel-Token"))
    }

    func testRelayBaseURLIncludesTunnelPath() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await relay().projects()
        XCTAssertEqual(lastRequest.url?.path, "/t/tun_1/project")
    }

    // --- read endpoints: request shape + parsing -----------------------------

    func testProjectsParsesList() async throws {
        StubURLProtocol.enqueue(body: #"[{"id":"p1","worktree":"/w","time":{"created":1,"updated":2},"sandboxes":[]}]"#)
        let p = try await direct().projects()
        XCTAssertEqual(lastRequest.url?.path, "/project")
        XCTAssertEqual(p.first?.id, "p1")
    }

    func testSessionsPassesDirectoryQuery() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().sessions(directory: "/srv/work")
        XCTAssertEqual(lastRequest.url?.path, "/session")
        XCTAssertTrue(lastRequest.url!.query!.contains("directory=/srv/work")
                      || lastRequest.url!.query!.contains("directory=%2Fsrv%2Fwork"))
    }

    func testGetSessionByIdNeedsNoDirectory() async throws {
        StubURLProtocol.enqueue(body: #"{"id":"ses_9","projectID":"p","directory":"/w","title":"","version":"1","time":{"created":1,"updated":2}}"#)
        let s = try await direct().getSession(id: "ses_9")
        XCTAssertEqual(lastRequest.url?.path, "/session/ses_9")
        XCTAssertEqual(s.id, "ses_9")
    }

    func testMessagesPageReadsNextCursorHeader() async throws {
        StubURLProtocol.enqueue(headers: ["X-Next-Cursor": "cur_42"], body: "[]")
        let page = try await direct().messagesPage(directory: "/w", sessionID: "ses_1", limit: 5)
        XCTAssertTrue(lastRequest.url!.query!.contains("limit=5"))
        XCTAssertEqual(page.nextCursor, "cur_42")
    }

    func testMessagesPageForwardsBeforeCursor() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().messagesPage(directory: "/w", sessionID: "ses_1", limit: 20, before: "cur_7")
        XCTAssertTrue(lastRequest.url!.query!.contains("before=cur_7"))
    }

    // --- write endpoints -----------------------------------------------------

    func testAbortPostsToAbort() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().abort(directory: "/w", sessionID: "ses_1")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertEqual(lastRequest.url?.path, "/session/ses_1/abort")
    }

    func testRenameSessionUsesPatch() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().renameSession(directory: "/w", sessionID: "ses_1", title: "New")
        XCTAssertEqual(lastRequest.httpMethod, "PATCH")
        XCTAssertEqual(lastRequest.url?.path, "/session/ses_1")
    }

    func testDeleteSessionUsesDelete() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().deleteSession(directory: "/w", sessionID: "ses_1")
        XCTAssertEqual(lastRequest.httpMethod, "DELETE")
        XCTAssertEqual(lastRequest.url?.path, "/session/ses_1")
    }

    func testShareSessionReturnsUpdatedSession() async throws {
        StubURLProtocol.enqueue(body: #"{"id":"ses_1","projectID":"p","directory":"/w","title":"","version":"1","time":{"created":1,"updated":2}}"#)
        let s = try await direct().shareSession(directory: "/w", sessionID: "ses_1")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertEqual(lastRequest.url?.path, "/session/ses_1/share")
        XCTAssertEqual(s.id, "ses_1")
    }

    // --- error handling ------------------------------------------------------

    func testUnauthorizedThrows() async {
        StubURLProtocol.enqueue(401)
        do { _ = try await direct().projects(); XCTFail("401 must throw") } catch {}
    }

    func testServerErrorThrows() async {
        StubURLProtocol.enqueue(500, body: "boom")
        do { _ = try await direct().projects(); XCTFail("500 must throw") } catch {}
    }

    // --- remaining read endpoints --------------------------------------------

    func testSessionsList() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().sessions(directory: "/w")
        XCTAssertEqual(lastRequest.url?.path, "/session")
    }

    func testSessionDiff() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().sessionDiff(directory: "/w", sessionID: "ses_1")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/session/ses_1/diff"))
    }

    func testSessionTodos() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().sessionTodos(directory: "/w", sessionID: "ses_1")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/session/ses_1/todo"))
    }

    func testCommands() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().commands(directory: "/w")
        XCTAssertEqual(lastRequest.url?.path, "/command")
    }

    func testAgents() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().agents()
        XCTAssertEqual(lastRequest.url?.path, "/agent")
    }

    func testListDirectory() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().listDirectory(path: "/w")
        XCTAssertEqual(lastRequest.url?.path, "/file")
        XCTAssertTrue(lastRequest.url!.query!.contains("path=."))
    }

    func testReadFile() async throws {
        StubURLProtocol.enqueue(body: #"{"content":"hello world"}"#)
        let text = try await direct().readFile(directory: "/w", path: "a.txt")
        XCTAssertEqual(lastRequest.url?.path, "/file/content")
        XCTAssertEqual(text, "hello world")
    }

    func testPermissionsList() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().permissions(directory: "/w")
        XCTAssertEqual(lastRequest.url?.path, "/permission")
    }

    func testQuestionsList() async throws {
        StubURLProtocol.enqueue(body: "[]")
        _ = try await direct().questions(directory: "/w")
        XCTAssertEqual(lastRequest.url?.path, "/question")
    }

    // --- remaining write endpoints -------------------------------------------

    func testReplyPermission() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().replyPermission(directory: "/w", requestID: "per_1", reply: "once")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertTrue(lastRequest.url!.path.contains("per_1"))
    }

    func testRejectQuestion() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().rejectQuestion(directory: "/w", requestID: "que_1")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertTrue(lastRequest.url!.path.contains("que_1"))
    }

    func testRevertSession() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().revertSession(directory: "/w", sessionID: "ses_1", messageID: "m9")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/session/ses_1/revert"))
    }

    func testUnrevertSession() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().unrevertSession(directory: "/w", sessionID: "ses_1")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/session/ses_1/unrevert"))
    }

    func testUnshareSession() async throws {
        StubURLProtocol.enqueue(body: #"{"id":"ses_1","projectID":"p","directory":"/w","title":"","version":"1","time":{"created":1,"updated":2}}"#)
        _ = try await direct().unshareSession(directory: "/w", sessionID: "ses_1")
        XCTAssertEqual(lastRequest.httpMethod, "DELETE")
        XCTAssertTrue(lastRequest.url!.path.contains("/share"))
    }

    func testRunShellExtractsOutput() async throws {
        StubURLProtocol.enqueue(body: #"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","output":"total 0"}}]}
        """#)
        let out = try await direct().runShell(directory: "/w", sessionID: "ses_1", command: "ls")
        XCTAssertTrue(lastRequest.url!.path.contains("/shell"))
        XCTAssertEqual(out, "total 0")
    }

    func testRemoveProviderAuth() async throws {
        StubURLProtocol.enqueue(body: "true")
        try await direct().removeProviderAuth(providerID: "openai")
        XCTAssertEqual(lastRequest.httpMethod, "DELETE")
        XCTAssertTrue(lastRequest.url!.path.contains("openai"))
    }

    func testProviderAuthMethods() async throws {
        StubURLProtocol.enqueue(body: "{}")
        _ = try await direct().providerAuthMethods()
        XCTAssertEqual(lastRequest.url?.path, "/provider/auth")
    }

    func testSetProviderKeyPuts() async throws {
        StubURLProtocol.enqueue(body: "true")
        try await direct().setProviderKey(providerID: "openai", key: "sk-123")
        XCTAssertEqual(lastRequest.httpMethod, "PUT")
        XCTAssertTrue(lastRequest.url!.path.contains("openai"))
    }

    func testProvidersParsesConfig() async throws {
        StubURLProtocol.enqueue(body: #"{"providers":[]}"#)
        _ = try await direct().providers()
        XCTAssertEqual(lastRequest.url?.path, "/config/providers")
    }

    func testRunCommandPosts() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().runCommand(directory: "/w", sessionID: "ses_1", command: "init")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/session/ses_1/command"))
    }

    func testReplyQuestionPosts() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().replyQuestion(directory: "/w", requestID: "que_1", answers: [["A"]])
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertTrue(lastRequest.url!.path.contains("que_1"))
    }

    func testSendPromptPostsToMessage() async throws {
        StubURLProtocol.enqueue(body: "{}")
        try await direct().sendPrompt(directory: "/w", sessionID: "ses_1", text: "hello",
                                      providerID: "zai", modelID: "glm-5.2", agent: "build")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/session/ses_1/message"))
    }

    func testCreateSessionReturnsSession() async throws {
        StubURLProtocol.enqueue(body: #"{"id":"ses_new","projectID":"p","directory":"/w","title":"T","version":"1","time":{"created":1,"updated":2}}"#)
        let s = try await direct().createSession(directory: "/w", title: "T")
        XCTAssertEqual(lastRequest.httpMethod, "POST")
        XCTAssertEqual(lastRequest.url?.path, "/session")
        XCTAssertEqual(s.id, "ses_new")
    }

    func testCreateSessionServerErrorThrows() async {
        StubURLProtocol.enqueue(422, body: "nope")
        do {
            _ = try await direct().createSession(directory: "/w")
            XCTFail("422 must throw")
        } catch {}
    }

    // --- event stream builder + decode-failure path --------------------------

    func testEventStreamBuiltForDirectMode() {
        XCTAssertNotNil(direct().eventStream(directory: ""))
    }

    func testEventStreamBuiltWithAuthAndTunnel() {
        // Exercises the password (Basic) + tunnelToken header branches.
        XCTAssertNotNil(relay(token: "tok_1", password: "pw").eventStream(directory: ""))
    }

    func testDecodeFailureThrows() async {
        // Valid HTTP 200 but a body that can't decode to [Project] → the decode
        // catch branch (the error-logging path in `get`).
        StubURLProtocol.enqueue(body: "not json")
        do { _ = try await direct().projects(); XCTFail("bad body must throw") } catch {}
    }

    func testForgetResetsConfigAndState() {
        let c = direct(password: "pw")
        c.forget()
        XCTAssertFalse(c.connected)
        // forget() resets to a fresh default config (mode defaults to .relay).
        XCTAssertEqual(c.config, ConnectionConfig())
        XCTAssertNil(c.config.password)
    }

    // --- connect / lifecycle -------------------------------------------------

    func testConnectSucceedsAgainstHealthyServer() async {
        StubURLProtocol.enqueue(body: #"{"healthy":true,"version":"1.2.3"}"#)
        let c = direct()
        await c.connect()
        XCTAssertTrue(c.connected)
        XCTAssertEqual(c.version, "1.2.3")
        XCTAssertNil(c.error)
    }

    func testConnectFailsOnServerError() async {
        StubURLProtocol.enqueue(500, body: "boom")
        let c = direct()
        await c.connect()
        XCTAssertFalse(c.connected)
        XCTAssertNotNil(c.error)
    }

    func testDisconnectClearsState() async {
        StubURLProtocol.enqueue(body: #"{"healthy":true,"version":"1"}"#)
        let c = direct()
        await c.connect()
        c.disconnect()
        XCTAssertFalse(c.connected)
        XCTAssertEqual(c.version, "")
    }

    func testApplyPairingUpdatesConfig() {
        let c = direct()
        XCTAssertTrue(c.applyPairing("opencode://pair?relay=https://r.example&tunnel=tun_1&token=tok_1"))
        XCTAssertEqual(c.config.mode, .relay)
        XCTAssertEqual(c.config.tunnelID, "tun_1")
    }

    func testApplyPairingRejectsGarbage() {
        XCTAssertFalse(direct().applyPairing("not a pairing link"))
    }

    // --- push registration (relay-only branch) -------------------------------

    func testRegisterPushTokenSkippedInDirectMode() async {
        // Direct mode: no relay/tunnel → no request made.
        await direct().registerPushToken("dtok")
        XCTAssertTrue(StubURLProtocol.seen.isEmpty)
    }

    func testRegisterPushTokenPostsInRelayMode() async {
        StubURLProtocol.enqueue(body: "ok")
        await relay().registerPushToken("dtok")
        XCTAssertTrue(lastRequest.url!.path.hasSuffix("/api/devices"))
        XCTAssertEqual(lastRequest.httpMethod, "POST")
    }

    // --- busy-session activity reducer ---------------------------------------

    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    func testActivityMarksSessionBusyThenIdle() {
        let c = direct()
        // An assistant message with no completion time → the session is busy.
        c.applyActivity(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"m","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#))
        XCTAssertTrue(c.busySessions.contains("ses_1"))
        // Completed → no longer busy.
        c.applyActivity(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"m","sessionID":"ses_1","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#))
        XCTAssertFalse(c.busySessions.contains("ses_1"))
    }

    func testActivityPartDeltaMarksBusy() {
        let c = direct()
        c.applyActivity(event(#"""
        {"payload":{"type":"message.part.delta","properties":{"sessionID":"ses_2","messageID":"m","partID":"p","field":"text","delta":"x"}}}
        """#))
        XCTAssertTrue(c.busySessions.contains("ses_2"))
    }

    func testActivityIgnoresUserMessage() {
        let c = direct()
        c.applyActivity(event(#"{"payload":{"type":"message.updated","properties":{"sessionID":"ses_3","info":{"id":"m","sessionID":"ses_3","role":"user","time":{"created":1}}}}}"#))
        XCTAssertFalse(c.busySessions.contains("ses_3"))
    }
}
