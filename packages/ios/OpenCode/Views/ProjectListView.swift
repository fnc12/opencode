import SwiftUI

struct ProjectListView: View {
    var server: ServerConnection
    @State private var projects: [Project] = []
    @State private var loading = true
    @State private var error: String?

    @State private var path: [AppRoute] = []
    @State private var showOpenFolder = false
    @AppStorage("openFolder.lastPath") private var folderPath = "/"
    @State private var creating = false
    @State private var createError: String?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if loading {
                    ProgressView("Loading projects...")
                } else if let error {
                    ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
                } else if projects.isEmpty {
                    ContentUnavailableView {
                        Label("No projects yet", systemImage: "folder")
                    } description: {
                        Text("Open a folder on the server to start a new session.")
                    } actions: {
                        Button("Open a folder") { showOpenFolder = true }
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("projects.openFolder.empty")
                    }
                } else {
                    ProjectTableView(
                        projects: projects,
                        onSelect: { path.append(.sessions($0)) },
                        onRefresh: { await load() }
                    )
                    .ignoresSafeArea(edges: .bottom)
                }
            }
            .navigationTitle("Projects")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Open folder", systemImage: "plus") { showOpenFolder = true }
                        .accessibilityIdentifier("projects.openFolder")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Disconnect", systemImage: "xmark.circle") { server.disconnect() }
                        .tint(.secondary)
                }
            }
            .navigationDestination(for: AppRoute.self) { route in
                switch route {
                case .sessions(let project):
                    SessionListView(server: server, project: project, path: $path)
                case .session(let session):
                    SessionView(session: session, server: server)
                }
            }
            .sheet(isPresented: $showOpenFolder) {
                OpenFolderSheet(server: server, path: $folderPath, creating: creating, error: createError) {
                    Task { await openFolder() }
                }
            }
            .task { await load() }
        }
    }

    private func openFolder() async {
        let directory = folderPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !directory.isEmpty else { return }
        creating = true
        createError = nil
        do {
            let session = try await server.createSession(directory: directory)
            creating = false
            showOpenFolder = false
            path.append(.session(session))
        } catch {
            creating = false
            createError = error.localizedDescription
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
