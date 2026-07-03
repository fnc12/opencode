import SwiftUI

/// A session's aggregate changes — a list of files with ± counts. Tapping a file
/// opens its full colored diff on its own screen (inlining every diff on a phone
/// is unreadable). Mirrors a desktop "Changes" panel but paged for mobile.
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
                List(diffs) { file in
                    NavigationLink {
                        DiffFileDetail(file: file)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: statusIcon(file.status)).foregroundStyle(statusColor(file.status))
                            Text(file.file ?? "?")
                                .font(.footnote.monospaced()).fontWeight(.medium)
                                .lineLimit(1).truncationMode(.middle)
                            Spacer(minLength: 8)
                            if file.additions > 0 { Text("+\(file.additions)").foregroundStyle(.green).font(.caption.monospaced()) }
                            if file.deletions > 0 { Text("−\(file.deletions)").foregroundStyle(.red).font(.caption.monospaced()) }
                        }
                    }
                }
                .listStyle(.plain)
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

private func statusIcon(_ status: String?) -> String {
    switch status {
    case "added": return "plus.circle.fill"
    case "deleted": return "minus.circle.fill"
    default: return "pencil.circle.fill"
    }
}
private func statusColor(_ status: String?) -> Color {
    switch status {
    case "added": return .green
    case "deleted": return .red
    default: return .orange
    }
}

/// One file's full colored diff on its own screen — selectable, with a copy button.
struct DiffFileDetail: View {
    let file: SessionFileDiff

    var body: some View {
        ScrollView([.horizontal, .vertical]) {
            Text(DiffFileDetail.colored(file.patch ?? ""))
                .font(.system(size: 12, design: .monospaced))
                .textSelection(.enabled)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle((file.file as NSString?)?.lastPathComponent ?? "Diff")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { UIPasteboard.general.string = file.patch } label: {
                    Image(systemName: "doc.on.doc")
                }
                .accessibilityLabel("Copy diff")
            }
        }
    }

    /// Colors a unified diff: green added lines, red removed, dimmed headers.
    static func colored(_ patch: String, maxLines: Int = 4000) -> AttributedString {
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
