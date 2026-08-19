import XCTest
import SnapshotTesting
@testable import OpenCode

/// Screenshot (swift-snapshot-testing) coverage for the hand-laid-out UIKit
/// message rendering — `MessageCell` + `MessageMetrics` layout, which plain unit
/// tests can't assert visually and which sat at 0%. Each golden pins one bubble
/// state; a layout regression (wrong height, clipped text, misplaced role/meta)
/// fails the diff. Mirrors the Android Paparazzi layer.
///
/// Record/update goldens: run with `record: .all` (see below) or delete the
/// `__Snapshots__` PNG and re-run; verify: the normal test run.
@MainActor
final class MessageCellSnapshotTests: XCTestCase {
    /// A cell configured + laid out at a fixed width, on a dark backdrop (the app
    /// is dark) so `.label` text is visible in the golden.
    private func card(_ rendered: RenderedMessage, width: CGFloat = 390) -> UIView {
        let cell = MessageCell(style: .default, reuseIdentifier: nil)
        cell.overrideUserInterfaceStyle = .dark
        cell.configure(rendered)
        let height = MessageMetrics.height(for: rendered, cellWidth: width)
        cell.frame = CGRect(x: 0, y: 0, width: width, height: height)
        cell.layoutIfNeeded()

        let container = UIView(frame: cell.frame)
        container.overrideUserInterfaceStyle = .dark
        container.backgroundColor = UIColor(white: 0.11, alpha: 1) // app background
        container.addSubview(cell)
        return container
    }

    private func text(_ s: String) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [
            .font: MessageMetrics.bodyFont, .foregroundColor: UIColor.label,
        ])
    }

    func testAssistantTextBubble() {
        let rendered = RenderedMessage(
            roleText: "build", roleColor: .systemGreen, metaText: "1k→2k",
            blocks: [.text(text("Here is the reply to your question — split into a clear sentence."))],
            bubbleColor: UIColor.white.withAlphaComponent(0.06))
        assertSnapshot(of: card(rendered), as: .image)
    }

    func testUserBubble() {
        let rendered = RenderedMessage(
            roleText: "You", roleColor: .systemBlue, metaText: nil,
            blocks: [.text(text("Fix the parser and add a test, please."))],
            bubbleColor: UIColor.systemBlue.withAlphaComponent(0.12))
        assertSnapshot(of: card(rendered), as: .image)
    }

    func testMultiParagraphWraps() {
        let rendered = RenderedMessage(
            roleText: "build", roleColor: .systemGreen, metaText: "3k→1k",
            blocks: [
                text("First paragraph that is long enough to wrap onto more than a single line in the bubble."),
                text("Second paragraph after a gap."),
            ].map { MessageBlock.text($0) },
            bubbleColor: UIColor.white.withAlphaComponent(0.06))
        assertSnapshot(of: card(rendered), as: .image)
    }

    func testCodeAndTableBubble() {
        // A code block + a table block in one bubble → exercises MessageCell's
        // code/table height + subview builders (makeCodeBlockView / makeTableView).
        let code = NSAttributedString(
            string: "func f() -> Int {\n    return 42\n}",
            attributes: [.font: CodeBlockView.font, .foregroundColor: UIColor.label])
        let table = MessageTable(header: ["Name", "Type"], rows: [["id", "Int"], ["title", "String"]])
        let rendered = RenderedMessage(
            roleText: "build", roleColor: .systemGreen, metaText: "2k→1k",
            blocks: [.text(text("Here's the function and its columns:")), .code(code), .table(table)],
            bubbleColor: UIColor.white.withAlphaComponent(0.06))
        assertSnapshot(of: card(rendered), as: .image)
    }
}
