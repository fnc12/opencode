import XCTest
import UIKit
@testable import OpenCode

/// The message-detail screen renders body/thinking through `MarkdownRenderer`
/// (not raw text), so markdown markers must be consumed and formatting applied —
/// this is what stops the detail from showing raw `**bold**` / `#` / `- ` syntax.
final class MarkdownRendererTests: XCTestCase {
    private let font = UIFont.systemFont(ofSize: 16)

    func testBoldStripsMarkersAndBolds() {
        let s = MarkdownRenderer.attributed("**bold** rest", font: font, color: .label)
        XCTAssertEqual(s.string, "bold rest", "the ** markers must be removed")
        let boldFont = s.attribute(.font, at: 0, effectiveRange: nil) as? UIFont
        XCTAssertTrue(boldFont?.fontDescriptor.symbolicTraits.contains(.traitBold) ?? false,
                      "**…** must render as bold")
    }

    func testInlineCodeStripsBackticks() {
        let s = MarkdownRenderer.attributed("run `ls -la` now", font: font, color: .label)
        XCTAssertEqual(s.string, "run ls -la now", "backticks removed")
        let codeFont = s.attribute(.font, at: 4, effectiveRange: nil) as? UIFont
        XCTAssertTrue(codeFont?.fontDescriptor.symbolicTraits.contains(.traitMonoSpace) ?? false,
                      "`code` must render monospaced")
    }

    func testHeaderStripsHashes() {
        let s = MarkdownRenderer.attributed("## Results", font: font, color: .label)
        XCTAssertEqual(s.string, "Results", "the # markers must be removed")
        let headerFont = s.attribute(.font, at: 0, effectiveRange: nil) as? UIFont
        XCTAssertTrue((headerFont?.pointSize ?? 0) > font.pointSize, "header renders larger")
    }
}
