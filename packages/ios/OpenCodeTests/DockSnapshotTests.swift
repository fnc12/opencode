import XCTest
import SwiftUI
import SnapshotTesting
@testable import OpenCode

/// Screenshot goldens for the static SwiftUI docks above the composer — the
/// live UI states the user actually reacts to (a permission ask, running tools,
/// a todo pill), all 0%-covered by unit tests. Rendered dark, fixed width. Views
/// with entrance/shimmer/typing animations are intentionally excluded here (their
/// goldens would be non-deterministic) and covered separately.
@MainActor
final class DockSnapshotTests: XCTestCase {
    private let dark = UITraitCollection(userInterfaceStyle: .dark)

    private func card<V: View>(_ view: V, width: CGFloat = 390) -> some View {
        view
            .frame(width: width)
            .background(Color(white: 0.11))
    }

    // NOTE: RunningToolsPill is deliberately NOT snapshotted — it's a
    // TimelineView showing elapsed time + an animated spinner, so its golden
    // would be non-deterministic. Its logic is covered by RunningToolsTests.

    func testPermissionDock() {
        let dock = PermissionDock(
            request: PermissionRequest(id: "per_1", sessionID: "s", action: "bash",
                                       resources: ["rm -rf build/"]),
            onReply: { _ in })
        assertSnapshot(of: card(dock), as: .image(layout: .sizeThatFits, traits: dark))
    }

    func testTodoPillMixed() {
        let pill = TodoPill(
            todos: [
                TodoItem(content: "Read the schema", status: "completed", priority: "high"),
                TodoItem(content: "Rename the node", status: "in_progress", priority: "high"),
                TodoItem(content: "Run the tests", status: "pending", priority: "medium"),
            ],
            onTap: {})
        assertSnapshot(of: card(pill), as: .image(layout: .sizeThatFits, traits: dark))
    }

    // NOTE: QuestionDock is NOT snapshotted — it has a SwiftUI entrance animation
    // (@State appeared → opacity 0 until onAppear's withAnimation runs), which a
    // static snapshot captures before it resolves (blank). It's covered live by
    // QuestionDockUITests (XCUITest) instead.
}
