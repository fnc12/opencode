import XCTest
import SwiftUI
import SnapshotTesting
@testable import OpenCode

/// Snapshot goldens for self-contained iOS components (0%-covered): the UIKit
/// code + table block views, and the SwiftUI project/session list rows. Static
/// inputs, dark appearance, pinned width.
@MainActor
final class ViewSnapshotTests: XCTestCase {
    private let dark = UITraitCollection(userInterfaceStyle: .dark)

    private func decode<T: Decodable>(_ json: String) -> T {
        try! JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    /// A UIKit view on a dark backdrop, laid out at a fixed width.
    private func card(_ view: UIView, width: CGFloat, height: CGFloat) -> UIView {
        view.overrideUserInterfaceStyle = .dark
        view.frame = CGRect(x: 0, y: 0, width: width, height: height)
        view.layoutIfNeeded()
        let container = UIView(frame: view.frame)
        container.overrideUserInterfaceStyle = .dark
        container.backgroundColor = UIColor(white: 0.11, alpha: 1)
        container.addSubview(view)
        return container
    }

    private func rowCard<V: View>(_ view: V, width: CGFloat = 390) -> some View {
        view.frame(width: width).padding(.vertical, 6).background(Color(white: 0.11))
    }

    // --- UIKit block views ---------------------------------------------------

    func testCodeBlockView() {
        let code = NSAttributedString(
            string: "func greet(_ name: String) -> String {\n    return \"Hi, \\(name)\"\n}",
            attributes: [.font: CodeBlockView.font, .foregroundColor: UIColor.label])
        let view = CodeBlockView(code: code)
        assertSnapshot(of: card(view, width: 390, height: CodeBlockView.height(for: code)), as: .image)
    }

    func testTableBlockView() {
        let table = MessageTable(header: ["Name", "Type"], rows: [["id", "Int"], ["title", "String"]])
        let view = TableBlockView(table: table, font: MessageMetrics.bodyFont)
        assertSnapshot(of: card(view, width: 390, height: TableBlockView.height(for: table, font: MessageMetrics.bodyFont)), as: .image)
    }

    // --- SwiftUI list rows ---------------------------------------------------

    func testProjectRow() {
        let project: Project = decode(#"{"id":"p1","worktree":"/Users/me/sources/sqlite_orm","time":{"created":1,"updated":2},"sandboxes":[]}"#)
        assertSnapshot(of: rowCard(ProjectRow(project: project)), as: .image(layout: .sizeThatFits, traits: dark))
    }

    // NOTE: SessionRow is NOT snapshotted — it shows a relative "time ago" label
    // (and a spinner when busy), so its golden is non-deterministic. Its state
    // logic (busy indicator) is covered by SessionStore/list tests instead.
}
