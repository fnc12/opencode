import SwiftUI

struct SessionListView: View {
    var server: ServerConnection
    let project: Project
    @State private var sessions: [Session] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading sessions...")
            } else if let error {
                ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
            } else if sessions.isEmpty {
                ContentUnavailableView("No Sessions", systemImage: "bubble.left.and.bubble.right", description: Text("No sessions in this project"))
            } else {
                List(sessions) { session in
                    NavigationLink(destination: SessionView(session: session, server: server)) {
                        SessionRow(session: session)
                    }
                }
                .listStyle(.plain)
                .refreshable { await load() }
            }
        }
        .navigationTitle(project.name ?? project.worktree.components(separatedBy: "/").last ?? "Sessions")
        .task { await load() }
    }

    private func load() async {
        loading = sessions.isEmpty
        error = nil
        do {
            sessions = try await server.sessions(directory: project.worktree)
            sessions.sort { $0.time.updated > $1.time.updated }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}
