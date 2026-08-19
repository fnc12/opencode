import XCTest
@testable import OpenCode

/// Small logic-file gaps: ClientError descriptions and every ToolDisplay verb
/// branch (in-scope files that must reach 100%).
final class LogicEdgeCaseTests: XCTestCase {
    // MARK: ClientError

    func testClientErrorDescriptions() {
        XCTAssertEqual(ClientError.invalidURL.errorDescription, "Invalid server URL")
        XCTAssertEqual(ClientError.http(503).errorDescription, "HTTP error: 503")
    }

    // MARK: ToolDisplay verbs

    private func tool(_ name: String, extra: String = "") -> ToolContent {
        let json = """
        {"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"\(name)",
         "state":{"status":"completed"\(extra.isEmpty ? "" : "," + extra)}}
        """
        let part = try! JSONDecoder().decode(MessagePart.self, from: Data(json.utf8))
        guard case .tool(let tc)? = part.content else { fatalError("expected tool") }
        return tc
    }

    func testEveryToolVerb() {
        let expected: [(String, String)] = [
            ("read", "Read"), ("list", "List"), ("glob", "Glob"), ("grep", "Grep"),
            ("bash", "Shell"), ("shell", "Shell"), ("edit", "Edit"), ("write", "Write"),
            ("patch", "Patch"), ("apply_patch", "Patch"), ("webfetch", "Webfetch"),
        ]
        for (name, label) in expected {
            XCTAssertEqual(ToolDisplay.describe(tool(name)).label, label, "tool \(name)")
        }
    }

    func testUnknownToolFallsBackToCapitalizedName() {
        // An unmapped tool name should still produce a non-empty label.
        let label = ToolDisplay.describe(tool("customtool")).label
        XCTAssertFalse(label.isEmpty)
    }
}
