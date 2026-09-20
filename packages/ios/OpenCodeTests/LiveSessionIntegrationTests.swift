import XCTest
@testable import OpenCode

/// End-to-end against a *real* OpenCode server, exercising the production path
/// (`EventStream` → `ServerEvent` → `SessionStore`): create a session, send a
/// prompt, and assert the assistant response streams in incrementally.
///
/// Opt-in — skipped unless the server is provided via environment:
///   OPENCODE_TEST_BASE   e.g. http://127.0.0.1:4096   (required)
///   OPENCODE_TEST_DIR    a project worktree on that server (required)
///   OPENCODE_TEST_PROVIDER / OPENCODE_TEST_MODEL  (default opencode / deepseek-v4-flash-free)
@MainActor
final class LiveSessionIntegrationTests: XCTestCase {
    func testLiveStreamingEndToEnd() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["OPENCODE_TEST_BASE"], let dir = env["OPENCODE_TEST_DIR"] else {
            throw XCTSkip("set OPENCODE_TEST_BASE and OPENCODE_TEST_DIR to run the live e2e test")
        }
        let provider = env["OPENCODE_TEST_PROVIDER"] ?? "opencode"
        let model = env["OPENCODE_TEST_MODEL"] ?? "deepseek-v4-flash-free"
        let encodedDir = dir.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? dir

        // Create a fresh session.
        let created = try await postJSON(base + "/session?directory=\(encodedDir)", body: ["title": "xctest live"])
        let sessionID = try XCTUnwrap((created as? [String: Any])?["id"] as? String)

        // Subscribe with the production stack.
        let server = ServerConnection()
        server.config = ConnectionConfig(mode: .direct, directURL: base)
        let store = SessionStore()
        store.setInitial([])

        let streamTask = Task { @MainActor in
            guard let stream = server.eventStream(directory: dir) else { return }
            let decoder = JSONDecoder()
            for try await data in stream.frames() {
                if let event = try? decoder.decode(ServerEvent.self, from: data) {
                    store.apply(event, sessionID: sessionID)
                }
            }
        }
        defer { streamTask.cancel() }
        try await Task.sleep(nanoseconds: 1_000_000_000) // let the stream attach

        // Generate activity.
        _ = try await postJSON(base + "/session/\(sessionID)/message?directory=\(encodedDir)", body: [
            "parts": [["type": "text", "text": "Reply with a short haiku about databases. No preamble."]],
            "model": ["providerID": provider, "modelID": model],
        ])

        // Wait for the assistant text to assemble.
        var assistant = ""
        for _ in 0..<120 {
            try await Task.sleep(nanoseconds: 500_000_000)
            assistant = assistantText(store)
            if !assistant.isEmpty, isAssistantComplete(store) { break }
        }

        XCTAssertFalse(assistant.isEmpty, "assistant text should stream in over SSE")
        XCTAssertTrue(store.messages.contains { if case .assistant = $0.info { return true } else { return false } })
    }

    /// Exercises cursor pagination against a *real* session through the production
    /// `ServerConnection.messagesPage` path — the newest page returns a bounded set
    /// plus an `X-Next-Cursor`, and paging `before:` that cursor returns strictly
    /// older, non-overlapping messages. This is the guard for the 15MB-session fix:
    /// opening pulls one small page instead of the whole (tens-of-MB) transcript.
    ///
    /// Opt-in — relay mode, set:
    ///   OPENCODE_TEST_RELAY   e.g. https://relay.shubat.org
    ///   OPENCODE_TEST_TUNNEL  tun_…
    ///   OPENCODE_TEST_TOKEN   the per-tunnel app token
    ///   OPENCODE_TEST_SESSION ses_…   (an existing session with >5 messages)
    ///   OPENCODE_TEST_DIR     that session's project directory
    func testLivePaginationCursor() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let relay = env["OPENCODE_TEST_RELAY"], let tunnel = env["OPENCODE_TEST_TUNNEL"],
              let token = env["OPENCODE_TEST_TOKEN"], let sessionID = env["OPENCODE_TEST_SESSION"],
              let dir = env["OPENCODE_TEST_DIR"] else {
            throw XCTSkip("set OPENCODE_TEST_RELAY/TUNNEL/TOKEN/SESSION/DIR to run the live pagination test")
        }
        let server = ServerConnection()
        server.config = ConnectionConfig(mode: .relay, relayURL: relay, tunnelID: tunnel, token: token)

        // Newest page: bounded, and there's more history behind it.
        let first = try await server.messagesPage(directory: dir, sessionID: sessionID, limit: 5)
        XCTAssertLessThanOrEqual(first.messages.count, 5, "the newest page is capped at the limit")
        XCTAssertFalse(first.messages.isEmpty, "the session should have messages")
        let cursor = try XCTUnwrap(first.nextCursor, "a session with >5 messages must hand back a next cursor")

        // Older page: strictly older ids, no overlap with the newest page.
        let older = try await server.messagesPage(directory: dir, sessionID: sessionID, limit: 5, before: cursor)
        XCTAssertFalse(older.messages.isEmpty, "paging before the cursor returns older history")
        let newestIDs = Set(first.messages.map(\.id))
        let olderIDs = Set(older.messages.map(\.id))
        XCTAssertTrue(newestIDs.isDisjoint(with: olderIDs), "older page must not repeat the newest page")

        // prependOlder stitches them into one ascending transcript, deduped.
        let store = await SessionStore()
        await store.setInitial(first.messages)
        await store.prependOlder(older.messages)
        let ids = await store.messages.map(\.id)
        XCTAssertEqual(ids.count, newestIDs.count + olderIDs.count, "no duplicates after merge")
    }

    // MARK: helpers

    private func assistantText(_ store: SessionStore) -> String {
        var out = ""
        for message in store.messages where isAssistant(message) {
            for part in message.parts { if case .text(let t)? = part.content { out += t } }
        }
        return out
    }

    private func isAssistant(_ message: MessageWithParts) -> Bool {
        if case .assistant = message.info { return true }
        return false
    }

    private func isAssistantComplete(_ store: SessionStore) -> Bool {
        store.messages.contains { message in
            if case .assistant(let info) = message.info { return info.time.completed != nil }
            return false
        }
    }

    @discardableResult
    private func postJSON(_ urlString: String, body: [String: Any]) async throws -> Any {
        var request = URLRequest(url: try XCTUnwrap(URL(string: urlString)))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            throw NSError(domain: "live", code: code, userInfo: [NSLocalizedDescriptionKey: String(decoding: data, as: UTF8.self)])
        }
        return try JSONSerialization.jsonObject(with: data)
    }
}
