import SwiftUI

/// A folder browser for the server's filesystem: navigate into subfolders (or
/// type a path), then "Open here" to start a new session in the current folder.
/// This is how you begin work on a fresh server.
struct OpenFolderSheet: View {
    var server: ServerConnection
    @Binding var path: String
    var creating: Bool
    var error: String?
    let onCreate: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var entries: [FileEntry] = []
    @State private var loading = false
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Image(systemName: "folder").foregroundStyle(.secondary)
                    TextField("/path/on/server", text: $path)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(.footnote, design: .monospaced))
                        .accessibilityIdentifier("openFolder.path")
                        .onSubmit { Task { await load(path) } }
                    if path != "/" && !path.isEmpty {
                        Button { Task { await load(parent(of: path)) } } label: {
                            Image(systemName: "arrow.up")
                        }
                        .accessibilityIdentifier("openFolder.up")
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                Divider()

                List {
                    if loading {
                        HStack { Spacer(); ProgressView(); Spacer() }
                    } else if let loadError {
                        Text(loadError).font(.caption).foregroundStyle(.secondary)
                    } else {
                        let folders = entries.filter(\.isDirectory)
                        if folders.isEmpty {
                            Text("No subfolders here").font(.caption).foregroundStyle(.secondary)
                        }
                        ForEach(folders) { entry in
                            Button { Task { await load(entry.absolute) } } label: {
                                Label(entry.name, systemImage: "folder.fill")
                                    .foregroundStyle(.primary)
                            }
                            .accessibilityIdentifier("dir.\(entry.name)")
                        }
                    }
                }
                .listStyle(.plain)

                if let error {
                    Text(error).font(.caption).foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal)
                }
            }
            .navigationTitle("Open folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if creating {
                        ProgressView()
                    } else {
                        Button("Open here") { onCreate() }
                            .disabled(path.trimmingCharacters(in: .whitespaces).isEmpty)
                            .accessibilityIdentifier("openFolder.create")
                    }
                }
            }
            .task { await load(path.isEmpty ? "/" : path) }
        }
    }

    private func load(_ newPath: String) async {
        let target = newPath.isEmpty ? "/" : newPath
        loading = true
        loadError = nil
        do {
            let result = try await server.listDirectory(path: target)
            path = target
            entries = result.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        } catch {
            loadError = "Can't open \(target)"
        }
        loading = false
    }

    private func parent(of p: String) -> String {
        let trimmed = (p.hasSuffix("/") && p.count > 1) ? String(p.dropLast()) : p
        guard let slash = trimmed.lastIndex(of: "/"), slash != trimmed.startIndex else { return "/" }
        return String(trimmed[..<slash])
    }
}
