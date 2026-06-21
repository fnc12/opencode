import SwiftUI

struct ProjectRow: View {
    let project: Project

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(project.name ?? project.worktree.components(separatedBy: "/").last ?? "Unknown")
                .font(.system(.body, weight: .medium))

            Text(shortenPath(project.worktree))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(.vertical, 4)
    }

    private func shortenPath(_ path: String) -> String {
        guard let home = path.range(of: "/Users/") else { return path }
        let afterUsers = path[home.upperBound...]
        if let slash = afterUsers.firstIndex(of: "/") {
            return "~" + afterUsers[slash...]
        }
        return path
    }
}
