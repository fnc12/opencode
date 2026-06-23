import XCTest
@testable import OpenCode

/// Decoding the OpenCode server's SSE event envelope. Frames are real-shaped:
/// the global stream wraps the event in `payload` alongside `directory`/`project`,
/// the instance stream is flat. Both must decode; unknown/`sync` events fall back.
final class ServerEventTests: XCTestCase {
    private func decode(_ json: String) throws -> ServerEvent {
        try JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    func testDecodesWrappedMessagePartDelta() throws {
        let json = """
        {"directory":"/x","project":"p","payload":{"id":"evt_1","type":"message.part.delta",
        "properties":{"sessionID":"ses_1","messageID":"msg_1","partID":"prt_1","field":"text","delta":"Hel"}}}
        """
        guard case .partDelta(let delta) = try decode(json) else { return XCTFail("expected .partDelta") }
        XCTAssertEqual(delta.sessionID, "ses_1")
        XCTAssertEqual(delta.partID, "prt_1")
        XCTAssertEqual(delta.field, "text")
        XCTAssertEqual(delta.delta, "Hel")
    }

    func testDecodesFlatInstanceFrame() throws {
        // No `payload` wrapper (the instance `/event` form).
        let json = """
        {"id":"evt_2","type":"message.part.delta",
        "properties":{"sessionID":"s","messageID":"m","partID":"p","field":"text","delta":"x"}}
        """
        guard case .partDelta(let delta) = try decode(json) else { return XCTFail("expected .partDelta") }
        XCTAssertEqual(delta.delta, "x")
    }

    func testDecodesMessageUpdatedAssistant() throws {
        let json = """
        {"payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{
        "id":"msg_a","sessionID":"ses_1","role":"assistant","time":{"created":2000},
        "modelID":"m","providerID":"p","agent":"build","cost":0,
        "tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":0,"write":0}}}}}}
        """
        guard case .messageUpdated(let sid, let info) = try decode(json) else { return XCTFail("expected .messageUpdated") }
        XCTAssertEqual(sid, "ses_1")
        guard case .assistant(let assistant) = info else { return XCTFail("expected assistant") }
        XCTAssertEqual(assistant.agent, "build")
        XCTAssertEqual(assistant.tokens.output, 5)
    }

    func testDecodesMessagePartUpdatedText() throws {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"sessionID":"ses_1",
        "part":{"id":"prt_1","sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hello"},"time":1.0}}}
        """
        guard case .partUpdated(_, let part) = try decode(json) else { return XCTFail("expected .partUpdated") }
        guard case .text(let text)? = part.content else { return XCTFail("expected text content") }
        XCTAssertEqual(text, "Hello")
    }

    func testSyncPayloadIsIgnoredAsOther() throws {
        let json = #"{"payload":{"type":"sync","syncEvent":{"type":"message.updated.1"}}}"#
        guard case .other(let type) = try decode(json) else { return XCTFail("expected .other") }
        XCTAssertEqual(type, "sync")
    }

    func testUnknownTypeIsOtherAndDoesNotThrow() throws {
        let json = #"{"payload":{"type":"server.connected","properties":{}}}"#
        guard case .other(let type) = try decode(json) else { return XCTFail("expected .other") }
        XCTAssertEqual(type, "server.connected")
    }
}
