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

    // --- detail-screen tool output + todo sheet ------------------------------

    private func toolContent(_ json: String) -> ToolContent {
        let part = try! JSONDecoder().decode(MessagePart.self, from: Data(json.utf8))
        guard case .tool(let tc)? = part.content else { fatalError("expected tool") }
        return tc
    }

    func testToolOutputDiff() {
        let tc = toolContent(#"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"edit","state":{"status":"completed","output":"Edit applied.","metadata":{"diff":"@@ -1,2 +1,2 @@\n int main() {\n-  return 0;\n+  return 1;"}}}"#)
        assertSnapshot(of: rowCard(ToolOutputView(tool: tc)), as: .image(layout: .sizeThatFits, traits: dark))
    }

    func testToolOutputPlain() {
        let tc = toolContent(#"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","output":"total 0\ndrwxr-xr-x"}}"#)
        assertSnapshot(of: rowCard(ToolOutputView(tool: tc)), as: .image(layout: .sizeThatFits, traits: dark))
    }

    func testToolOutputFileRead() {
        // A file read: the `<type>file` marker makes isFileRead true → the content
        // is syntax-highlighted by filePath extension (the `read` branch).
        let tc = toolContent(#"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"read","state":{"status":"completed","input":{"filePath":"main.swift"},"output":"<type>file</type>\n<content>let x = 1\nprint(x)</content>"}}"#)
        assertSnapshot(of: rowCard(ToolOutputView(tool: tc)), as: .image(layout: .sizeThatFits, traits: dark))
    }

    func testToolOutputTodoChecklist() {
        // The todowrite branch renders TodoChecklist with mixed statuses (checked,
        // in-progress dotted, open) — covers ToolOutputView's todo case + TodoChecklist.
        let tc = toolContent(#"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"todowrite","state":{"status":"completed","output":"ok","metadata":{"todos":[{"content":"Parse the file","status":"completed"},{"content":"Split the parser","status":"in_progress"},{"content":"Write tests","status":"pending"}]}}}"#)
        assertSnapshot(of: rowCard(ToolOutputView(tool: tc)), as: .image(layout: .sizeThatFits, traits: dark))
    }

    func testTodoSheet() {
        let todos: [TodoItem] = [
            decode(#"{"content":"Read the schema","status":"completed","priority":"high"}"#),
            decode(#"{"content":"Rename the node","status":"in_progress","priority":"high"}"#),
            decode(#"{"content":"Run the suite","status":"pending","priority":"medium"}"#),
        ]
        assertSnapshot(of: rowCard(TodoSheet(todos: todos)), as: .image(layout: .sizeThatFits, traits: dark))
    }
}
