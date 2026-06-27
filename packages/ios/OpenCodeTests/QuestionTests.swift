import XCTest
@testable import OpenCode

final class QuestionDecodeTests: XCTestCase {
    private func decode(_ json: String) throws -> ServerEvent {
        try JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    func testDecodesQuestionAsked() throws {
        let json = """
        {"payload":{"type":"question.v2.asked","properties":{"id":"q_1","sessionID":"ses_1","questions":[
          {"question":"Which DB?","header":"Pick","options":[
            {"label":"Postgres","description":"relational"},{"label":"SQLite","description":"embedded"}],
           "multiple":false,"custom":true}]}}}
        """
        guard case .questionAsked(let req) = try decode(json) else { return XCTFail("expected .questionAsked") }
        XCTAssertEqual(req.id, "q_1")
        XCTAssertEqual(req.sessionID, "ses_1")
        XCTAssertEqual(req.questions.count, 1)
        XCTAssertEqual(req.questions[0].question, "Which DB?")
        XCTAssertEqual(req.questions[0].options.map(\.label), ["Postgres", "SQLite"])
        XCTAssertFalse(req.questions[0].allowsMultiple)
    }

    func testDecodesQuestionRepliedAndRejected() throws {
        let replied = #"{"payload":{"type":"question.v2.replied","properties":{"sessionID":"ses_1","requestID":"q_1","answers":[["Postgres"]]}}}"#
        guard case .questionResolved(let s1, let r1) = try decode(replied) else { return XCTFail("expected resolved (replied)") }
        XCTAssertEqual(s1, "ses_1"); XCTAssertEqual(r1, "q_1")

        let rejected = #"{"payload":{"type":"question.v2.rejected","properties":{"sessionID":"ses_1","requestID":"q_1"}}}"#
        guard case .questionResolved(_, let r2) = try decode(rejected) else { return XCTFail("expected resolved (rejected)") }
        XCTAssertEqual(r2, "q_1")
    }
}

@MainActor
final class QuestionStoreTests: XCTestCase {
    private let sessionID = "ses_1"

    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    private func asked(id: String, session: String) -> ServerEvent {
        event("""
        {"type":"question.v2.asked","properties":{"id":"\(id)","sessionID":"\(session)","questions":[
          {"question":"Q?","header":"H","options":[{"label":"A","description":""}]}]}}
        """)
    }

    private func resolved(id: String, session: String) -> ServerEvent {
        event(#"{"type":"question.v2.rejected","properties":{"sessionID":"\#(session)","requestID":"\#(id)"}}"#)
    }

    func testAskedAddsThenResolvedRemoves() {
        let store = SessionStore()
        store.apply(asked(id: "q_1", session: sessionID), sessionID: sessionID)
        XCTAssertEqual(store.pendingQuestions.map(\.id), ["q_1"])
        store.apply(resolved(id: "q_1", session: sessionID), sessionID: sessionID)
        XCTAssertTrue(store.pendingQuestions.isEmpty)
    }

    func testDeduplicates() {
        let store = SessionStore()
        store.apply(asked(id: "q_1", session: sessionID), sessionID: sessionID)
        store.apply(asked(id: "q_1", session: sessionID), sessionID: sessionID)
        XCTAssertEqual(store.pendingQuestions.count, 1)
    }

    func testIgnoresOtherSessions() {
        let store = SessionStore()
        store.apply(asked(id: "q_1", session: "OTHER"), sessionID: sessionID)
        XCTAssertTrue(store.pendingQuestions.isEmpty)
    }
}
