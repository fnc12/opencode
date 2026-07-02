import SwiftUI

/// A session's aggregate changes — one card per file with its unified diff
/// colored (green additions / red deletions), long lines scrolling sideways.
struct DiffView: View {
    let session: Session
    var server: ServerConnection

    @State private var diffs: [SessionFileDiff] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading changes…")
            } else if let error {
                ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
            } else if diffs.isEmpty {
                ContentUnavailableView("No changes", systemImage: "checkmark.circle")
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(diffs) { file in
                            DiffFileCard(file: file)
                        }
                    }
                    .padding(12)
                }
            }
        }
        .navigationTitle("Changes")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        do {
            diffs = try await server.sessionDiff(directory: session.directory, sessionID: session.id)
                .filter { ($0.patch?.isEmpty == false) || $0.additions > 0 || $0.deletions > 0 }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}

/// One file: a header (path + status + ± counts) and its colored diff.
private struct DiffFileCard: View {
    let file: SessionFileDiff

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: statusIcon).foregroundStyle(statusColor).font(.caption)
                Text(file.file ?? "?")
                    .font(.footnote.monospaced()).fontWeight(.medium)
                    .lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 8)
                if file.additions > 0 { Text("+\(file.additions)").foregroundStyle(.green).font(.caption.monospaced()) }
                if file.deletions > 0 { Text("−\(file.deletions)").foregroundStyle(.red).font(.caption.monospaced()) }
            }
            if let patch = file.patch, !patch.isEmpty {
                ScrollView(.horizontal, showsIndicators: true) {
                    Text(DiffFileCard.colored(patch))
                        .font(.system(size: 12, design: .monospaced))
                        .textSelection(.enabled)
                        .padding(8)
                }
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
    }

    private var statusIcon: String {
        switch file.status {
        case "added": return "plus.circle.fill"
        case "deleted": return "minus.circle.fill"
        default: return "pencil.circle.fill"
        }
    }
    private var statusColor: Color {
        switch file.status {
        case "added": return .green
        case "deleted": return .red
        default: return .orange
        }
    }

    /// Colors a unified diff: green added lines, red removed, dimmed hunk/file
    /// headers. Bounded so a huge patch never builds an unbounded string.
    static func colored(_ patch: String, maxLines: Int = 1200) -> AttributedString {
        var out = AttributedString()
        let lines = patch.split(separator: "\n", omittingEmptySubsequences: false)
        for line in lines.prefix(maxLines) {
            let s = String(line)
            var run = AttributedString(s + "\n")
            if s.hasPrefix("+") && !s.hasPrefix("+++") {
                run.foregroundColor = Color(red: 0.13, green: 0.55, blue: 0.24)
                run.backgroundColor = Color.green.opacity(0.12)
            } else if s.hasPrefix("-") && !s.hasPrefix("---") {
                run.foregroundColor = Color(red: 0.80, green: 0.20, blue: 0.20)
                run.backgroundColor = Color.red.opacity(0.12)
            } else if s.hasPrefix("@@") {
                run.foregroundColor = .accentColor
            } else if s.hasPrefix("diff ") || s.hasPrefix("index ")
                        || s.hasPrefix("+++") || s.hasPrefix("---") {
                run.foregroundColor = Color.secondary.opacity(0.7)
            } else {
                run.foregroundColor = .primary
            }
            out += run
        }
        if lines.count > maxLines {
            var more = AttributedString("… \(lines.count - maxLines) more lines")
            more.foregroundColor = .secondary
            out += more
        }
        return out
    }
}
