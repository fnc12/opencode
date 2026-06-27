import SwiftUI

struct SessionListView: View {
    var server: ServerConnection
    let project: Project
    @Binding var path: [AppRoute]

    @State private var sessions: [Session] = []
    @State private var loading = true
    @State private var error: String?
    @State private var creating = false

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading sessions...")
            } else if let error {
                ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
            } else if sessions.isEmpty {
                ContentUnavailableView {
                    Label("No sessions yet", systemImage: "bubble.left.and.bubble.right")
                } description: {
                    Text("Start a new session in this project.")
                } actions: {
                    Button("New session") { Task { await newSession() } }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("sessions.new.empty")
                }
            } else {
                List(sessions) { session in
                    NavigationLink(value: AppRoute.session(session)) {
                        SessionRow(session: session)
                    }
                }
                .listStyle(.plain)
                .refreshable { await load() }
            }
        }
        .navigationTitle(project.name ?? project.worktree.components(separatedBy: "/").last ?? "Sessions")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if creating {
                    ProgressView()
                } else {
                    Button("New session", systemImage: "square.and.pencil") { Task { await newSession() } }
                        .accessibilityIdentifier("sessions.new")
                }
            }
        }
        .task { await load() }
    }

    private func newSession() async {
        creating = true
        do {
            let session = try await server.createSession(directory: project.worktree)
            creating = false
            path.append(.session(session))
        } catch {
            creating = false
            self.error = error.localizedDescription
        }
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
