import XCTest
@testable import OpenCode

/// Decode-branch coverage for the model layer — every ServerEvent type and every
/// PartContent type — to drive ServerEvent/PartContent/MessageInfo to 100%.
final class ModelDecodeTests: XCTestCase {
    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }
    private func part(_ json: String) -> PartContent? {
        try! JSONDecoder().decode(MessagePart.self, from: Data(json.utf8)).content
    }
    private func wrap(_ type: String, _ props: String) -> String {
        #"{"payload":{"type":"\#(type)","properties":\#(props)}}"#
    }

    // MARK: ServerEvent branches

    func testPartRemoved() {
        guard case .partRemoved(let sid, let mid, let pid) =
            event(wrap("message.part.removed", #"{"sessionID":"s","messageID":"m","partID":"p"}"#))
        else { return XCTFail("partRemoved") }
        XCTAssertEqual([sid, mid, pid], ["s", "m", "p"])
    }

    func testMessageRemoved() {
        guard case .messageRemoved(let sid, let mid) =
            event(wrap("message.removed", #"{"sessionID":"s","messageID":"m"}"#))
        else { return XCTFail("messageRemoved") }
        XCTAssertEqual([sid, mid], ["s", "m"])
    }

    func testSessionUpdatedRevert() {
        guard case .sessionUpdated(let sid, let revert) =
            event(wrap("session.updated", #"{"info":{"id":"s","projectID":"p","directory":"/w","title":"","version":"1","time":{"created":1,"updated":2},"revert":{"messageID":"m9"}}}"#))
        else { return XCTFail("sessionUpdated") }
        XCTAssertEqual(sid, "s")
        XCTAssertEqual(revert, "m9")
    }

    func testTodoUpdated() {
        guard case .todoUpdated(let sid, let todos) =
            event(wrap("todo.updated", #"{"sessionID":"s","todos":[{"content":"x","status":"pending"}]}"#))
        else { return XCTFail("todoUpdated") }
        XCTAssertEqual(sid, "s")
        XCTAssertEqual(todos.count, 1)
    }

    func testPermissionV1Name() {
        guard case .permissionAsked(let r) =
            event(wrap("permission.asked", #"{"id":"per_1","sessionID":"s","permission":"edit","patterns":["*.ts"]}"#))
        else { return XCTFail("permission v1") }
        XCTAssertEqual(r.id, "per_1")
    }

    func testQuestionRejected() {
        guard case .questionResolved(let sid, let rid) =
            event(wrap("question.v2.rejected", #"{"sessionID":"s","requestID":"que_1"}"#))
        else { return XCTFail("question rejected") }
        XCTAssertEqual([sid, rid], ["s", "que_1"])
    }

    func testUnknownEventIsOther() {
        guard case .other(let type) = event(wrap("server.connected", "{}")) else { return XCTFail("other") }
        XCTAssertEqual(type, "server.connected")
    }

    // MARK: PartContent branches

    private func toolPart(_ tool: String) -> String {
        #"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"\#(tool)","state":{"status":"completed"}}"#
    }

    func testStepStartAndFinish() {
        if case .stepStart? = part(#"{"id":"p","sessionID":"s","messageID":"m","type":"step-start"}"#) {} else { XCTFail("stepStart") }
        if case .stepFinish? = part(#"{"id":"p","sessionID":"s","messageID":"m","type":"step-finish"}"#) {} else { XCTFail("stepFinish") }
    }

    func testPatchPart() {
        if case .patch? = part(#"{"id":"p","sessionID":"s","messageID":"m","type":"patch","hash":"abc","files":["a.txt"]}"#) {} else { XCTFail("patch") }
    }

    func testCompactionPart() {
        if case .compaction(let auto)? = part(#"{"id":"p","sessionID":"s","messageID":"m","type":"compaction","auto":true}"#) {
            XCTAssertTrue(auto)
        } else { XCTFail("compaction") }
    }

    func testReasoningAndTextParts() {
        if case .reasoning(let t)? = part(#"{"id":"p","sessionID":"s","messageID":"m","type":"reasoning","text":"why"}"#) {
            XCTAssertEqual(t, "why")
        } else { XCTFail("reasoning") }
        if case .text(let t)? = part(#"{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"hi"}"#) {
            XCTAssertEqual(t, "hi")
        } else { XCTFail("text") }
    }

    func testToolPart() {
        if case .tool? = part(toolPart("bash")) {} else { XCTFail("tool") }
    }
}
