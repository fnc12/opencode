import SwiftUI

struct ProjectListView: View {
    var server: ServerConnection
    @State private var projects: [Project] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading projects...")
                } else if let error {
                    ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
                } else if projects.isEmpty {
                    ContentUnavailableView("No Projects", systemImage: "folder", description: Text("No projects found on this server"))
                } else {
                    List(projects) { project in
                        NavigationLink(destination: SessionListView(server: server, project: project)) {
                            ProjectRow(project: project)
                        }
                    }
                    .listStyle(.plain)
                    .refreshable { await load() }
                }
            }
            .navigationTitle("Projects")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text(server.version)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Disconnect", systemImage: "xmark.circle") {
                        server.disconnect()
                    }
                    .tint(.secondary)
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        loading = projects.isEmpty
        error = nil
        do {
            projects = try await server.projects()
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}
