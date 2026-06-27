import SwiftUI

struct SessionView: View {
    let session: Session
    var server: ServerConnection

    @State private var store = SessionStore()
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        content
            .safeAreaInset(edge: .bottom) {
                if !loading && error == nil {
                    VStack(spacing: 0) {
                        ForEach(store.pendingPermissions) { request in
                            PermissionDock(request: request) { reply in
                                Task { await handleReply(request, reply) }
                            }
                        }
                        ForEach(store.pendingQuestions) { request in
                            QuestionDock(
                                request: request,
                                onReply: { answers in Task { await handleQuestionReply(request, answers) } },
                                onReject: { Task { await handleQuestionReject(request) } })
                        }
                        ComposerView(server: server, session: session)
                    }
                }
            }
            .navigationTitle(session.title.isEmpty ? "Untitled" : session.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 10) {
                        if store.isBusy {
                            Button(role: .destructive) {
                                Task { try? await server.abort(directory: session.directory, sessionID: session.id) }
                            } label: {
                                Label("Stop", systemImage: "stop.circle.fill")
                            }
                            .tint(.red)
                            .accessibilityIdentifier("session.stop")
                        }
                        StreamStatusBadge(status: store.status)
                    }
                }
            }
            .task { await run() }
    }

    @ViewBuilder private var content: some View {
        if loading {
            ProgressView("Loading messages...")
        } else if let error {
            ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
        } else if store.messages.isEmpty {
            ContentUnavailableView("Start the conversation", systemImage: "bubble.left", description: Text("Send a message to begin"))
        } else {
            thread
        }
    }

    private var thread: some View {
        MessageListView(messages: store.messages, revision: store.revision)
            .ignoresSafeArea(.container, edges: .bottom)
    }

    /// Loads the message history, then consumes the SSE stream, reconnecting
    /// with exponential backoff until the view (and thus this task) goes away.
    private func run() async {
        do {
            let initial = try await server.messages(directory: session.directory, sessionID: session.id)
            store.setInitial(initial)
        } catch {
            self.error = error.localizedDescription
            loading = false
            return
        }
        loading = false

        // Seed permission + question requests already pending for this session.
        if let pending = try? await server.permissions(directory: session.directory) {
            store.setInitialPermissions(pending.filter { $0.sessionID == session.id })
        }
        if let pendingQuestions = try? await server.questions(directory: session.directory) {
            store.setInitialQuestions(pendingQuestions.filter { $0.sessionID == session.id })
        }
        // UI tests inject synthetic requests (after the seeds, so they win) so the
        // docks can be driven deterministically without configuring the server to "ask".
        if ProcessInfo.processInfo.arguments.contains("UITEST_PERMISSION") {
            store.setInitialPermissions([PermissionRequest(
                id: "uitest-perm", sessionID: session.id, action: "bash", resources: ["echo hello"])])
        }
        if ProcessInfo.processInfo.arguments.contains("UITEST_QUESTION") {
            store.setInitialQuestions([QuestionRequest(id: "uitest-q", sessionID: session.id, questions: [
                QuestionItem(question: "Which database?", header: "Pick one",
                             options: [QuestionOption(label: "Option A", description: "the first"),
                                       QuestionOption(label: "Option B", description: "the second")],
                             multiple: false, custom: false)])])
        }

        let decoder = JSONDecoder()
        var backoff: UInt64 = 500_000_000 // 0.5s
        while !Task.isCancelled {
            guard let stream = server.eventStream(directory: session.directory) else { break }
            store.setStatus(.connecting)
            do {
                for try await data in stream.frames() {
                    if Task.isCancelled { break }
                    backoff = 500_000_000 // healthy stream resets backoff
                    store.setStatus(.live)
                    guard let event = try? decoder.decode(ServerEvent.self, from: data) else { continue }
                    store.apply(event, sessionID: session.id)
                }
            } catch {
                if Task.isCancelled { break }
            }
            if Task.isCancelled { break }
            store.setStatus(.reconnecting)
            try? await Task.sleep(nanoseconds: backoff)
            backoff = min(backoff * 2, 10_000_000_000) // cap at 10s
        }
    }

    /// Answers a permission request and clears it locally right away.
    private func handleReply(_ request: PermissionRequest, _ reply: String) async {
        store.dismissPermission(id: request.id)
        try? await server.replyPermission(directory: session.directory, requestID: request.id, reply: reply)
    }

    private func handleQuestionReply(_ request: QuestionRequest, _ answers: [[String]]) async {
        store.dismissQuestion(id: request.id)
        try? await server.replyQuestion(directory: session.directory, requestID: request.id, answers: answers)
    }

    private func handleQuestionReject(_ request: QuestionRequest) async {
        store.dismissQuestion(id: request.id)
        try? await server.rejectQuestion(directory: session.directory, requestID: request.id)
    }
}

/// Small live/connecting/reconnecting indicator shown in the nav bar.
private struct StreamStatusBadge: View {
    let status: SessionStore.StreamStatus

    var body: some View {
        switch status {
        case .idle:
            EmptyView()
        case .live:
            Label("Live", systemImage: "circle.fill")
                .labelStyle(.titleAndIcon)
                .font(.caption2)
                .foregroundStyle(.green)
        case .connecting, .reconnecting:
            HStack(spacing: 4) {
                ProgressView().controlSize(.mini)
                Text(status == .connecting ? "Connecting" : "Reconnecting")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
