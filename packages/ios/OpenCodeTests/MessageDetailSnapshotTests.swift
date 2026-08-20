import XCTest
import SwiftUI
import SnapshotTesting
@testable import OpenCode

/// Snapshot of the full-screen message detail (MessageDetailView, 548 lines, 0%).
/// It renders synchronously from a message — thinking card, answer, tool cards —
/// so a fixed message pins the whole screen.
@MainActor
final class MessageDetailSnapshotTests: XCTestCase {
    private func decode(_ json: String) -> MessageWithParts {
        try! JSONDecoder().decode(MessageWithParts.self, from: Data(json.utf8))
    }

    func testAssistantDetail() {
        let message = decode(#"""
        {"info":{"id":"m1","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":10,"output":20,"reasoning":0,"cache":{"read":0,"write":0}}},
         "parts":[
           {"id":"p0","sessionID":"s","messageID":"m1","type":"reasoning","text":"Let me split the tokenizer and add a test."},
           {"id":"p1","sessionID":"s","messageID":"m1","type":"text","text":"Done — I split the tokenizer and **added a test**."},
           {"id":"p2","sessionID":"s","messageID":"m1","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","output":"ok\n2 passed","title":"cargo test"}}
         ]}
        """#)
        let view = MessageDetailView(message: message, onClose: {})
        assertSnapshot(of: view, as: .image(layout: .device(config: .iPhone13),
                                            traits: .init(userInterfaceStyle: .dark)))
    }

    private func snap(_ message: MessageWithParts, _ named: String = #function) {
        assertSnapshot(of: MessageDetailView(message: message, onClose: {}),
                       as: .image(layout: .device(config: .iPhone13),
                                  traits: .init(userInterfaceStyle: .dark)),
                       testName: named)
    }

    func testUserDetail() {
        snap(decode(#"{"info":{"id":"m","sessionID":"s","role":"user","time":{"created":1}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"Fix the parser and add a test."}]}"#))
    }

    func testDetailWithPatchAndError() {
        snap(decode(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"error":{"name":"E","data":{"message":"rate limited"}}},
         "parts":[
           {"id":"p1","sessionID":"s","messageID":"m","type":"patch","hash":"abc","files":["a.txt","b.txt"]},
           {"id":"p2","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"read","state":{"status":"error","error":"file not found"}}
         ]}
        """#))
    }

    /// A text part carrying a fenced code block (```swift … ```) plus a file
    /// attachment — exercises splitText's fence parsing, the CodeBlockRepresentable
    /// host, and the `.file` note branch.
    func testDetailWithCodeFenceAndFile() {
        snap(decode(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},
         "parts":[
           {"id":"p1","sessionID":"s","messageID":"m","type":"text","text":"Here is the fix:\n\n```swift\nfunc greet() -> String {\n  return \"hi\"\n}\n```\n\nThat should do it."},
           {"id":"p2","sessionID":"s","messageID":"m","type":"file","filename":"diff.patch","url":"data:text/plain;base64,aGk=","mime":"text/plain"}
         ]}
        """#))
    }

    // --- copy-all flattening (plainText) -------------------------------------

    private func plain(_ json: String) -> String {
        MessageDetailView(message: decode(json), onClose: {}).plainText
    }

    func testPlainTextFlattensEveryBlockType() {
        let text = plain(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},
         "parts":[
           {"id":"p0","sessionID":"s","messageID":"m","type":"reasoning","text":"pondering"},
           {"id":"p1","sessionID":"s","messageID":"m","type":"text","text":"Answer line.\n\n```swift\nlet x = 1\n```"},
           {"id":"p2","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","output":"2 passed"}},
           {"id":"p3","sessionID":"s","messageID":"m","type":"patch","hash":"h","files":["a.txt"]}
         ]}
        """#)
        XCTAssertTrue(text.contains("💭 Thinking"), "reasoning block is labelled")
        XCTAssertTrue(text.contains("Answer line."), "text block is included")
        XCTAssertTrue(text.contains("let x = 1"), "fenced code is flattened to its source")
        XCTAssertTrue(text.contains("2 passed"), "tool output is included")
        XCTAssertTrue(text.contains("Patch"), "patch note is included")
    }

    func testToggledExpandsThenCollapses() {
        // The thinking-card toggle: absent id → inserted; present id → removed.
        let expanded = MessageDetailView.toggled([], "t1")
        XCTAssertTrue(expanded.contains("t1"))
        let collapsed = MessageDetailView.toggled(expanded, "t1")
        XCTAssertFalse(collapsed.contains("t1"))
        // Other ids are untouched.
        XCTAssertEqual(MessageDetailView.toggled(["a"], "b"), ["a", "b"])
    }

    func testPlainTextSkipsUnrenderedPartTypes() {
        // A step-finish part isn't a detail block → the blocks builder's default
        // branch skips it; the surrounding text still flattens.
        let text = plain(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},
         "parts":[
           {"id":"p0","sessionID":"s","messageID":"m","type":"step-finish"},
           {"id":"p1","sessionID":"s","messageID":"m","type":"text","text":"After the step."}
         ]}
        """#)
        XCTAssertEqual(text, "After the step.")
    }

    func testPlainTextEditToolUsesDiff() {
        // An edit tool → the diff (not the output) is what gets copied.
        let text = plain(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},
         "parts":[{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"edit","state":{"status":"completed","output":"applied","metadata":{"diff":"-old\n+new"}}}]}
        """#)
        XCTAssertTrue(text.contains("+new"), "edit tool copies its diff")
    }
}
