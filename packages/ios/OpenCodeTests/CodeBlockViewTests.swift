import XCTest
import UIKit
@testable import OpenCode

/// Unit coverage for CodeBlockView's copy affordance — the `copyTapped` action
/// (clipboard write + haptic + icon flip) that a snapshot can't trigger.
@MainActor
final class CodeBlockViewTests: XCTestCase {
    private func findButton(_ view: UIView, id: String) -> UIButton? {
        if let b = view as? UIButton, b.accessibilityIdentifier == id { return b }
        for sub in view.subviews {
            if let found = findButton(sub, id: id) { return found }
        }
        return nil
    }

    func testCopyButtonCopiesCodeToPasteboard() {
        let code = NSAttributedString(string: "let answer = 42")
        let view = CodeBlockView(code: code, selectable: true)
        view.frame = CGRect(x: 0, y: 0, width: 320, height: CodeBlockView.height(for: code))
        view.layoutIfNeeded()

        UIPasteboard.general.string = "" // clear
        let button = findButton(view, id: "code.copy")
        XCTAssertNotNil(button, "the copy button must exist")
        button?.sendActions(for: .touchUpInside)
        XCTAssertEqual(UIPasteboard.general.string, "let answer = 42",
                       "tapping copy puts the code on the clipboard")
    }
}
