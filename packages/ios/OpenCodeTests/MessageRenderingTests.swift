import XCTest
@testable import OpenCode

/// Guards the "empty You bubble" regression: a message whose only part is
/// synthetic/ignored (or empty) must not be considered renderable, so the list
/// skips it. See `MessageWithParts.hasRenderableContent`.
final class MessageRenderingTests: XCTestCase {
    private func decode(_ json: String) throws -> MessageWithParts {
        try JSONDecoder().decode(MessageWithParts.self, from: Data(json.utf8))
    }

    private func userMessage(parts: String) -> String {
        """
        {"info":{"id":"m","sessionID":"s","role":"user","time":{"created":1}},"parts":[\(parts)]}
        """
    }
    private func assistantMessage(parts: String) -> String {
        """
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[\(parts)]}
        """
    }

    func testSyntheticOnlyMessageIsHidden() throws {
        // The exact filler POST /session/:id/shell posts as a user message.
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","synthetic":true,"text":"The following tool was executed by the user"}
        """
        XCTAssertFalse(try decode(userMessage(parts: part)).hasRenderableContent)
    }

    func testIgnoredOnlyMessageIsHidden() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","ignored":true,"text":"hidden"}
        """
        XCTAssertFalse(try decode(userMessage(parts: part)).hasRenderableContent)
    }

    func testEmptyTextMessageIsHidden() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","text":"   "}
        """
        XCTAssertFalse(try decode(assistantMessage(parts: part)).hasRenderableContent)
    }

    func testRealTextMessageIsVisible() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","text":"hello"}
        """
        XCTAssertTrue(try decode(userMessage(parts: part)).hasRenderableContent)
    }

    func testToolOnlyMessageIsVisible() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed"}}
        """
        XCTAssertTrue(try decode(assistantMessage(parts: part)).hasRenderableContent)
    }

    func testSyntheticTextButRealToolIsVisible() throws {
        let parts = """
        {"id":"p1","sessionID":"s","messageID":"m","type":"text","synthetic":true,"text":"filler"},
        {"id":"p2","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed"}}
        """
        XCTAssertTrue(try decode(assistantMessage(parts: parts)).hasRenderableContent)
    }
}
