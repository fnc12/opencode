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
}
