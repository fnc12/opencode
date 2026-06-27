import SwiftUI

/// Lets the user open a folder on the server by absolute path and start a new
/// session there — the way to begin work on a fresh server (no projects yet),
/// since the server filesystem can't be browsed from the client.
struct OpenFolderSheet: View {
    @Binding var path: String
    var creating: Bool
    var error: String?
    let onCreate: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Server folder") {
                    TextField("/absolute/path/to/project", text: $path)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(.body, design: .monospaced))
                        .accessibilityIdentifier("openFolder.path")
                }
                Section {
                    Text("Enter the absolute path of a folder on the OpenCode server. A new session opens there.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let error {
                    Section { Text(error).font(.caption).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Open folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if creating {
                        ProgressView()
                    } else {
                        Button("Create") { onCreate() }
                            .disabled(path.trimmingCharacters(in: .whitespaces).isEmpty)
                            .accessibilityIdentifier("openFolder.create")
                    }
                }
            }
        }
    }
}
