import SwiftUI

/// A repo file staged as prompt context — carries the ready-built file part.
struct FileAttachment: Identifiable {
    let id: UUID
    let filename: String
    var part: [String: Any]
    init(id: UUID = UUID(), filename: String, part: [String: Any]) {
        self.id = id; self.filename = filename; self.part = part
    }
}

/// Browse the server's filesystem and pick a file to attach as prompt context.
/// Navigates folders like the folder browser, but files are tappable too.
struct FilePickerSheet: View {
    let server: ServerConnection
    let startPath: String
    let onPick: (FileEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var path: String
    @State private var entries: [FileEntry] = []
    @State private var loading = false
    @State private var error: String?

    init(server: ServerConnection, startPath: String, onPick: @escaping (FileEntry) -> Void) {
        self.server = server
        self.startPath = startPath
        self.onPick = onPick
        _path = State(initialValue: startPath)
    }

    var body: some View {
        NavigationStack {
            List {
                if path != "/" {
                    Button { Task { await load(parent(of: path)) } } label: {
                        Label("..", systemImage: "arrow.up.left.circle")
                    }
                }
                ForEach(sortedEntries) { entry in
                    Button {
                        if entry.isDirectory {
                            Task { await load(entry.absolute) }
                        } else {
                            onPick(entry)
                            dismiss()
                        }
                    } label: {
                        Label(entry.name, systemImage: entry.isDirectory ? "folder.fill" : "doc.text")
                            .foregroundStyle(entry.isDirectory ? Color.accentColor : .primary)
                    }
                    .accessibilityIdentifier((entry.isDirectory ? "dir." : "file.") + entry.name)
                }
            }
            .overlay {
                if loading { ProgressView() }
                else if let error { ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error)) }
                else if entries.isEmpty { ContentUnavailableView("Empty folder", systemImage: "folder") }
            }
            .navigationTitle(path.components(separatedBy: "/").last?.isEmpty == false
                             ? path.components(separatedBy: "/").last! : "Files")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .task { await load(startPath) }
    }

    private var sortedEntries: [FileEntry] {
        entries.sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory && !$1.isDirectory }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private func load(_ newPath: String) async {
        let target = newPath.isEmpty ? "/" : newPath
        loading = true
        error = nil
        do {
            entries = try await server.listDirectory(path: target)
            path = target
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    private func parent(of p: String) -> String {
        let trimmed = p.count > 1 && p.hasSuffix("/") ? String(p.dropLast()) : p
        guard let slash = trimmed.lastIndex(of: "/") else { return "/" }
        let parent = String(trimmed[..<slash])
        return parent.isEmpty ? "/" : parent
    }
}
