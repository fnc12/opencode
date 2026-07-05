import XCTest
import UIKit
@testable import OpenCode

/// The detail screen renders an edit/write's unified diff (metadata.diff) as a
/// colored diff — additions green, deletions red, hunk headers blue — mirroring
/// the web client's ContentDiff.
final class DiffRendererTests: XCTestCase {
    func testAddDeleteHunkColors() {
        let diff = "@@ -1 +1 @@\n-old line\n+new line"
        let s = DiffRenderer.attributed(diff)
        let str = s.string as NSString

        let add = str.range(of: "+new line")
        let del = str.range(of: "-old line")
        let hunk = str.range(of: "@@")

        XCTAssertEqual(s.attribute(.foregroundColor, at: add.location, effectiveRange: nil) as? UIColor, .systemGreen)
        XCTAssertEqual(s.attribute(.foregroundColor, at: del.location, effectiveRange: nil) as? UIColor, .systemRed)
        XCTAssertEqual(s.attribute(.foregroundColor, at: hunk.location, effectiveRange: nil) as? UIColor, .systemBlue)
        // changed lines get a background tint so the whole row reads as changed
        XCTAssertNotNil(s.attribute(.backgroundColor, at: add.location, effectiveRange: nil))
        XCTAssertNotNil(s.attribute(.backgroundColor, at: del.location, effectiveRange: nil))
    }

    func testFileHeadersAreNotTreatedAsAddRemove() {
        // `+++`/`---` file headers must not be colored as additions/deletions.
        let s = DiffRenderer.attributed("--- a/x\n+++ b/x\n+added")
        let str = s.string as NSString
        let plus = s.attribute(.foregroundColor, at: str.range(of: "+++ b/x").location, effectiveRange: nil) as? UIColor
        XCTAssertEqual(plus, .secondaryLabel)
    }

    /// The model must decode metadata.diff so the renderer has something to draw.
    func testEditToolDecodesDiff() throws {
        let json = """
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"edit","state":{"status":"completed","output":"Edit applied successfully.","metadata":{"diff":"@@ -1 +1 @@\\n-a\\n+b"}}}]}
        """
        let msg = try JSONDecoder().decode(MessageWithParts.self, from: Data(json.utf8))
        guard case .tool(let tool)? = msg.parts.first?.content else { return XCTFail("no tool part") }
        XCTAssertEqual(tool.state.metadata?.diff, "@@ -1 +1 @@\n-a\n+b")
    }
}
