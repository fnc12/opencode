import XCTest
@testable import OpenCode

/// `isBusy` drives the Stop/abort button — true while an assistant message is
/// still generating (no completion time).
@MainActor
final class SessionBusyTests: XCTestCase {
    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    private func assistant(timeJSON: String) -> ServerEvent {
        event("""
        {"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"msg_a","sessionID":"ses_1","role":"assistant","time":\(timeJSON),
        "modelID":"m","providerID":"p","agent":"build","cost":0,
        "tokens":{"input":1,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}}}
        """)
    }

    func testBusyWhileInFlightThenIdleWhenComplete() {
        let store = SessionStore()
        XCTAssertFalse(store.isBusy, "no messages → not busy")

        store.apply(assistant(timeJSON: #"{"created":1}"#), sessionID: "ses_1")
        XCTAssertTrue(store.isBusy, "assistant with no completion time → busy")

        store.apply(assistant(timeJSON: #"{"created":1,"completed":2}"#), sessionID: "ses_1")
        XCTAssertFalse(store.isBusy, "assistant completed → not busy")
    }
}
