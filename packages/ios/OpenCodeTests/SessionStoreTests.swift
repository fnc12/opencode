import XCTest
@testable import OpenCode

/// Folding a stream of events into conversation state: upserts, ordering by
/// creation time, streaming text deltas, snapshot reconciliation, and filtering
/// by session.
@MainActor
final class SessionStoreTests: XCTestCase {
    private let sessionID = "ses_1"

    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    private func userUpdated(id: String, created: Double) -> ServerEvent {
        event("""
        {"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"\(id)","sessionID":"ses_1","role":"user","time":{"created":\(created)}}}}
        """)
    }

    private func assistantUpdated(id: String, created: Double) -> ServerEvent {
        event("""
        {"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"\(id)","sessionID":"ses_1","role":"assistant","time":{"created":\(created)},
        "modelID":"m","providerID":"p","agent":"build","cost":0,
        "tokens":{"input":1,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}}}
        """)
    }

    private func textDelta(messageID: String, partID: String, delta: String) -> ServerEvent {
        event("""
        {"type":"message.part.delta","properties":{"sessionID":"ses_1",
        "messageID":"\(messageID)","partID":"\(partID)","field":"text","delta":"\(delta)"}}
        """)
    }

    private func textPart(messageID: String, partID: String, text: String) -> ServerEvent {
        event("""
        {"type":"message.part.updated","properties":{"sessionID":"ses_1",
        "part":{"id":"\(partID)","sessionID":"ses_1","messageID":"\(messageID)","type":"text","text":"\(text)"},"time":1}}
        """)
    }

    private func assistantText(_ store: SessionStore) -> String {
        var out = ""
        for message in store.messages {
            if case .assistant = message.info {
                for part in message.parts { if case .text(let t)? = part.content { out += t } }
            }
        }
        return out
    }

    func testStreamsAndOrders() {
        let store = SessionStore()
        store.setInitial([])

        // Arrive out of order: assistant first, then the (earlier) user message.
        store.apply(assistantUpdated(id: "msg_a", created: 2000), sessionID: sessionID)
        store.apply(userUpdated(id: "msg_u", created: 1000), sessionID: sessionID)
        store.apply(textPart(messageID: "msg_u", partID: "prt_u", text: "Hi"), sessionID: sessionID)

        // Assistant text streams via deltas (no prior snapshot).
        store.apply(textDelta(messageID: "msg_a", partID: "prt_a", delta: "Hello"), sessionID: sessionID)
        store.apply(textDelta(messageID: "msg_a", partID: "prt_a", delta: " world"), sessionID: sessionID)

        XCTAssertEqual(store.messages.count, 2)
        XCTAssertEqual(store.messages.first?.id, "msg_u", "ordered by creation time, user first")
        XCTAssertEqual(store.messages.last?.id, "msg_a")
        XCTAssertEqual(assistantText(store), "Hello world")
    }

    func testSnapshotReconcilesDeltaBuiltText() {
        let store = SessionStore()
        store.setInitial([])
        store.apply(assistantUpdated(id: "msg_a", created: 1), sessionID: sessionID)
        store.apply(textDelta(messageID: "msg_a", partID: "prt_a", delta: "Hel"), sessionID: sessionID)
        // Authoritative snapshot for the same part id replaces the delta-built text.
        store.apply(textPart(messageID: "msg_a", partID: "prt_a", text: "Hello, world"), sessionID: sessionID)
        XCTAssertEqual(assistantText(store), "Hello, world")
    }

    func testIgnoresOtherSessions() {
        let store = SessionStore()
        store.setInitial([])
        store.apply(assistantUpdated(id: "msg_a", created: 1), sessionID: "DIFFERENT")
        XCTAssertTrue(store.messages.isEmpty, "events for another session must not leak in")
    }

    func testRevisionBumpsOnAppliedChange() {
        let store = SessionStore()
        store.setInitial([])
        let before = store.revision
        store.apply(userUpdated(id: "msg_u", created: 1), sessionID: sessionID)
        XCTAssertGreaterThan(store.revision, before)
    }
}
