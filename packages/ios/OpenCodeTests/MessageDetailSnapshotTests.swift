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
}
