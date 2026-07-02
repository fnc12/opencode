import SwiftUI

struct SessionListView: View {
    var server: ServerConnection
    let project: Project
    @Binding var path: [AppRoute]

    @State private var sessions: [Session] = []
    @State private var loading = true
    @State private var error: String?
    @State private var creating = false
    @State private var renameTarget: Session?
    @State private var renameText = ""

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
                SessionTableView(
                    sessions: sessions,
                    onSelect: { path.append(.session($0)) },
                    onDelete: { session in Task { await delete(session) } },
                    onRename: { startRename($0) },
                    onRefresh: { await load() }
                )
                .ignoresSafeArea(edges: .bottom)
            }
        }
        .alert("Rename session", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } })) {
            TextField("Title", text: $renameText)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Rename") { Task { await commitRename() } }
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

    private func delete(_ session: Session) async {
        do {
            try await server.deleteSession(directory: project.worktree, sessionID: session.id)
            sessions.removeAll { $0.id == session.id }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func startRename(_ session: Session) {
        renameTarget = session
        renameText = session.title
    }

    private func commitRename() async {
        guard let target = renameTarget else { return }
        let title = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        renameTarget = nil
        guard !title.isEmpty, title != target.title else { return }
        do {
            try await server.renameSession(directory: project.worktree, sessionID: target.id, title: title)
            await load()
        } catch {
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
