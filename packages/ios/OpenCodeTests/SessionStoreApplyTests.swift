import XCTest
@testable import OpenCode

/// Exercises the `SessionStore.apply` SSE reducer — the core of live streaming —
/// for each event type, driving events decoded from real `/global/event` JSON.
@MainActor
final class SessionStoreApplyTests: XCTestCase {
    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    private func store() -> SessionStore { SessionStore() }

    private let sid = "ses_1"

    func testMessageUpdatedAddsMessage() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":2000},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.messages.count, 1)
        XCTAssertEqual(s.messages.first?.id, "msg_a")
    }

    func testForeignSessionIgnored() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"OTHER","info":{"id":"x","sessionID":"OTHER","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.messages.count, 0, "an event for another session must be ignored")
    }

    func testPartUpdatedAddsPart() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        s.apply(event(#"""
        {"payload":{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"prt_1","sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hello"}}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.messages.first?.parts.count, 1)
    }

    func testPartDeltaAppendsText() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        s.apply(event(#"""
        {"payload":{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"prt_1","sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hel"}}}}
        """#), sessionID: sid)
        s.apply(event(#"""
        {"payload":{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"msg_a","partID":"prt_1","field":"text","delta":"lo"}}}
        """#), sessionID: sid)
        let text = s.messages.first?.parts.first.flatMap { p -> String? in
            if case .text(let t)? = p.content { return t }; return nil
        }
        XCTAssertEqual(text, "Hello")
    }

    func testMessageRemoved() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        s.apply(event(#"{"payload":{"type":"message.removed","properties":{"sessionID":"ses_1","messageID":"msg_a"}}}"#), sessionID: sid)
        XCTAssertEqual(s.messages.count, 0)
    }

    func testPermissionAskedThenReplied() {
        let s = store()
        s.apply(event(#"{"payload":{"type":"permission.v2.asked","properties":{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["ls"]}}}"#), sessionID: sid)
        XCTAssertEqual(s.pendingPermissions.count, 1)
        s.apply(event(#"{"payload":{"type":"permission.v2.replied","properties":{"sessionID":"ses_1","requestID":"per_1"}}}"#), sessionID: sid)
        XCTAssertEqual(s.pendingPermissions.count, 0)
    }

    func testQuestionAskedThenResolved() {
        let s = store()
        s.apply(event(#"{"payload":{"type":"question.v2.asked","properties":{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Which?","header":"Pick","options":[{"label":"A","description":"a"}]}]}}}"#), sessionID: sid)
        XCTAssertEqual(s.pendingQuestions.count, 1)
        s.apply(event(#"{"payload":{"type":"question.v2.replied","properties":{"sessionID":"ses_1","requestID":"que_1"}}}"#), sessionID: sid)
        XCTAssertEqual(s.pendingQuestions.count, 0)
    }

    func testUnmodeledEventNoOp() {
        let s = store()
        let before = s.revision
        s.apply(event(#"{"payload":{"type":"server.connected","properties":{}}}"#), sessionID: sid)
        XCTAssertEqual(s.revision, before, "an unmodeled event must not bump the revision")
    }

    // --- part.removed --------------------------------------------------------

    func testPartRemovedDropsPart() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        s.apply(event(#"""
        {"payload":{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"prt_1","sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hello"}}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.messages.first?.parts.count, 1)
        s.apply(event(#"""
        {"payload":{"type":"message.part.removed","properties":{"sessionID":"ses_1","messageID":"msg_a","partID":"prt_1"}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.messages.first?.parts.count, 0)
    }

    // --- todo.updated + session.updated (revert boundary) --------------------

    func testTodoUpdatedReplacesList() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"todo.updated","properties":{"sessionID":"ses_1","todos":[{"content":"a","status":"pending","priority":"high"},{"content":"b","status":"completed","priority":"low"}]}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.todos.count, 2)
    }

    func testSessionUpdatedSetsRevertBoundary() {
        let s = store()
        s.apply(event(#"""
        {"payload":{"type":"session.updated","properties":{"info":{"id":"ses_1","projectID":"p","directory":"/w","title":"","version":"1","time":{"created":1,"updated":2},"revert":{"messageID":"msg_9"}}}}}
        """#), sessionID: sid)
        XCTAssertEqual(s.revertMessageID, "msg_9")
    }

    // --- seed setters + status + isBusy --------------------------------------

    func testSeedSettersPopulateState() {
        let s = store()
        let perm: PermissionRequest = try! JSONDecoder().decode(
            PermissionRequest.self,
            from: Data(#"{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["ls"]}"#.utf8))
        s.setInitialPermissions([perm])
        XCTAssertEqual(s.pendingPermissions.count, 1)

        let question: QuestionRequest = try! JSONDecoder().decode(
            QuestionRequest.self,
            from: Data(#"{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Q","header":"H","options":[{"label":"A","description":"a"}]}]}"#.utf8))
        s.setInitialQuestions([question])
        XCTAssertEqual(s.pendingQuestions.count, 1)

        let todo: TodoItem = try! JSONDecoder().decode(
            TodoItem.self, from: Data(#"{"content":"x","status":"pending","priority":"high"}"#.utf8))
        s.setInitialTodos([todo])
        XCTAssertEqual(s.todos.count, 1)

        s.setRevert("msg_5")
        XCTAssertEqual(s.revertMessageID, "msg_5")
        s.setRevert(nil)
        XCTAssertNil(s.revertMessageID)
    }

    func testSetStatusIgnoresRepeatValue() {
        let s = store()
        XCTAssertEqual(s.status, .idle)
        s.setStatus(.live)
        XCTAssertEqual(s.status, .live)
        // Same value again → the guard early-returns (no observable churn).
        s.setStatus(.live)
        XCTAssertEqual(s.status, .live)
        s.setStatus(.reconnecting)
        XCTAssertEqual(s.status, .reconnecting)
    }

    func testIsBusyWhileAssistantGenerating() {
        let s = store()
        // Assistant message with no completion time → busy.
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":1},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        XCTAssertTrue(s.isBusy)
        // Completed → no longer busy.
        s.apply(event(#"""
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":1,"completed":2},"modelID":"m","providerID":"p","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """#), sessionID: sid)
        XCTAssertFalse(s.isBusy)
    }
}
