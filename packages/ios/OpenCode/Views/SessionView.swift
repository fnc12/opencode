import SwiftUI
import UIKit
import PhotosUI

struct SessionView: View {
    let session: Session
    var server: ServerConnection

    @State private var store = SessionStore()
    @State private var loading = true
    @State private var error: String?
    @State private var showDiff = false
    @State private var showShell = false
    @State private var showTodos = false
    @State private var showFilePicker = false
    @State private var fileAttachments: [FileAttachment] = []
    @State private var shareURL: String?
    @State private var shareItem: ShareURL?
    @State private var revertTarget: String?
    // Model + command pickers live here (the main window), not in the composer,
    // so presenting them doesn't tear down the keyboard-hosted input bar.
    @State private var providers: [ProviderInfo] = []
    @State private var commands: [CommandInfo] = []
    @State private var showModelPicker = false
    @State private var showCommands = false
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var showPhotoPicker = false
    /// Bumped on a 30s timer while busy so `isStuck` re-evaluates without events.
    @State private var stuckCheck = Date()
    @AppStorage("composer.providerID") private var providerID = ""
    @AppStorage("composer.modelID") private var modelID = ""

    var body: some View {
        ZStack {
            if let error {
                ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
            } else {
                // The whole screen is UIKit (message list + bottom bar) so the
                // keyboard is handled natively: the composer is the controller's
                // inputAccessoryView. Ignore SwiftUI's keyboard avoidance here.
                SessionContent(messages: visibleMessages,
                               revision: store.revision,
                               onRevert: { revertTarget = $0 },
                               showTyping: showThinking && !isStuck) {
                    bottomBar
                }
                .ignoresSafeArea(.keyboard, edges: .bottom)
                // Re-evaluate "stuck" every 30s while busy — a parked turn emits
                // no events, so nothing else would trigger the check.
                .task(id: store.isBusy) {
                    while store.isBusy && !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 30_000_000_000)
                        stuckCheck = Date()
                    }
                }
                if loading {
                    ProgressView("Loading messages...")
                }
            }
        }
        .navigationTitle(session.title.isEmpty ? "Untitled" : session.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 10) {
                    // Stop lives in the composer (send ↔ stop), like the web — not here.
                    Button { showShell = true } label: {
                        Image(systemName: "terminal")
                    }
                    .accessibilityIdentifier("session.shell")
                    .accessibilityLabel("Shell")
                    Button { showDiff = true } label: {
                        Image(systemName: "plusminus")
                    }
                    .accessibilityIdentifier("session.diff")
                    .accessibilityLabel("Changes")
                    Menu {
                        Button { Task { await share() } } label: {
                            Label("Share link", systemImage: "square.and.arrow.up")
                        }
                        if shareURL != nil {
                            Button(role: .destructive) { Task { await stopSharing() } } label: {
                                Label("Stop sharing", systemImage: "xmark.circle")
                            }
                        }
                    } label: {
                        Image(systemName: shareURL != nil ? "link.circle.fill" : "square.and.arrow.up")
                    }
                    .accessibilityIdentifier("session.share")
                    StreamStatusBadge(status: store.status)
                }
            }
        }
        .sheet(isPresented: $showShell) {
            ShellView(server: server, session: session)
        }
        .sheet(isPresented: $showTodos) {
            TodoSheet(todos: store.todos)
                .presentationDetents([.medium, .large])
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickerItems,
                      maxSelectionCount: 4, matching: .images)
        .sheet(isPresented: $showModelPicker) {
            ModelPickerView(providers: providers, providerID: $providerID, modelID: $modelID)
        }
        .sheet(isPresented: $showCommands) {
            CommandPickerView(commands: commands) { runCommand($0) }
        }
        .sheet(isPresented: $showFilePicker) {
            FilePickerSheet(server: server, startPath: session.directory) { entry in
                attachFile(entry)
            }
        }
        .sheet(isPresented: $showDiff) {
            NavigationStack {
                DiffView(session: session, server: server)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") { showDiff = false }
                        }
                    }
            }
        }
        .sheet(item: $shareItem) { item in
            ActivityView(items: [item.url])
        }
        .confirmationDialog("Revert to this message?",
                            isPresented: Binding(get: { revertTarget != nil },
                                                 set: { if !$0 { revertTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Revert", role: .destructive) {
                if let id = revertTarget { Task { await revert(id) } }
                revertTarget = nil
            }
            Button("Cancel", role: .cancel) { revertTarget = nil }
        } message: {
            Text("Undoes this message and everything after it, including file changes.")
        }
        .task {
            shareURL = session.share?.url
            await run()
        }
        .task { await loadComposerData() }
    }

    /// Loads the model + command lists here (not in the composer) so their
    /// pickers — presented from this view — actually see the data.
    private func loadComposerData() async {
        if providers.isEmpty { providers = (try? await server.providers()) ?? [] }
        if commands.isEmpty {
            commands = (try? await server.commands(directory: session.directory)) ?? []
        }
        if modelID.isEmpty { autoSelectDefault() }
    }

    /// Pick a sensible default model if none chosen — prefer a free `opencode` one.
    private func autoSelectDefault() {
        if let opencode = providers.first(where: { $0.id == "opencode" }) {
            let keys = opencode.models.keys.sorted()
            if let model = keys.first(where: { $0.contains("free") }) ?? keys.first {
                providerID = "opencode"; modelID = model; return
            }
        }
        if let provider = providers.first, let model = provider.models.keys.sorted().first {
            providerID = provider.id; modelID = model
        }
    }

    /// The agent is generating but hasn't streamed any answer text yet — show the
    /// typing cue. Once its text starts arriving, the text itself is the feedback.
    /// The agent is generating for this session (drives the composer send↔stop
    /// toggle). UITEST_TYPING forces it so the stop state is testable.
    private var isStreaming: Bool {
        store.isBusy || ProcessInfo.processInfo.environment["UITEST_TYPING"] != nil
    }

    private var showThinking: Bool {
        if ProcessInfo.processInfo.environment["UITEST_TYPING"] != nil { return true }
        guard store.isBusy else { return false }
        guard let last = store.messages.last else { return true }
        guard last.info.role == "assistant" else { return true }
        let hasText = last.parts.contains { part in
            guard part.isVisible, case .text(let t)? = part.content else { return false }
            return !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return !hasText
    }

    /// A "busy" turn is likely *stuck* — not thinking — when it has produced no
    /// text for a while (e.g. an unanswered permission on an old server that
    /// never surfaced the prompt). We then stop the endless typing dots and show
    /// a cancel hint instead; the red stop button already aborts. `stuckCheck`
    /// (a 30s tick, below) makes this re-evaluate even while no events arrive.
    private var isStuck: Bool {
        _ = stuckCheck
        guard store.isBusy, ProcessInfo.processInfo.environment["UITEST_TYPING"] == nil else { return false }
        guard let last = store.messages.last, case .assistant(let info) = last.info,
              info.time.completed == nil else { return false }
        let hasText = last.parts.contains { part in
            guard part.isVisible, case .text(let t)? = part.content else { return false }
            return !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        let ageMS = Date().timeIntervalSince1970 * 1000 - info.time.created
        return !hasText && ageMS > Self.stuckAfterMS
    }
    private static let stuckAfterMS: Double = 180_000 // 3 min with no output → treat as stuck

    /// Docks (permissions / questions) stacked above the composer — hosted inside
    /// the UIKit controller so it rides the keyboard with the list.
    /// Messages actually shown: renderable, and (if the session is reverted) only
    /// those before the revert boundary.
    private var visibleMessages: [MessageWithParts] {
        var msgs = store.messages
        if let revertID = store.revertMessageID,
           let idx = msgs.firstIndex(where: { $0.id == revertID }) {
            msgs = Array(msgs.prefix(idx))
        }
        return msgs.filter { $0.hasRenderableContent }
    }

    private var revertedCount: Int {
        guard let revertID = store.revertMessageID,
              let idx = store.messages.firstIndex(where: { $0.id == revertID }) else { return 0 }
        return store.messages.count - idx
    }

    @ViewBuilder private var bottomBar: some View {
        VStack(spacing: 0) {
            if store.revertMessageID != nil {
                Button { Task { await restore() } } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.uturn.backward")
                        Text("\(revertedCount) message\(revertedCount == 1 ? "" : "s") reverted")
                        Spacer()
                        Text("Restore").fontWeight(.semibold)
                    }
                    .font(.caption)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(Color.orange.opacity(0.18))
                    .foregroundStyle(.orange)
                }
                .buttonStyle(.plain)
            }
            if !store.todos.isEmpty {
                TodoPill(todos: store.todos) { showTodos = true }
            }
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
            if isStuck {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                    Text("This turn looks stuck — tap ■ to cancel it.")
                    Spacer()
                }
                .font(.caption)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(Color.orange.opacity(0.18))
                .foregroundStyle(.orange)
                .accessibilityIdentifier("session.stuck")
            }
            ComposerView(server: server, session: session,
                         fileAttachments: $fileAttachments, showFilePicker: $showFilePicker,
                         providers: $providers, commands: $commands,
                         showModelPicker: $showModelPicker, showCommands: $showCommands,
                         pickerItems: $pickerItems, showPhotoPicker: $showPhotoPicker,
                         isBusy: isStreaming)
        }
    }

    /// Loads the message history, then consumes the SSE stream, reconnecting
    /// with exponential backoff until the view (and thus this task) goes away.
    private func run() async {
        do {
            let initial = try await server.messages(directory: session.directory, sessionID: session.id)
            store.setInitial(initial)
            store.setRevert(session.revert?.messageID)
        } catch is CancellationError {
            loading = false // the .task was cancelled (e.g. a screen presented over us) — not an error
            return
        } catch let e as URLError where e.code == .cancelled {
            loading = false
            return
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
        if let todos = try? await server.sessionTodos(directory: session.directory, sessionID: session.id) {
            store.setInitialTodos(todos)
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
        if ProcessInfo.processInfo.arguments.contains("UITEST_TODO") {
            store.setInitialTodos([
                TodoItem(content: "Read the AST query schema", status: "completed", priority: "high"),
                TodoItem(content: "Rename qr → qualifiedColumnRefNode across the codebase", status: "in_progress", priority: "high"),
                TodoItem(content: "Run the test suite and verify 623 tests pass", status: "pending", priority: "medium"),
            ])
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

    /// Create (or reuse) the public share link and open the system share sheet.
    private func share() async {
        do {
            let updated = try await server.shareSession(directory: session.directory, sessionID: session.id)
            if let url = updated.share?.url, let u = URL(string: url) {
                shareURL = url
                shareItem = ShareURL(url: u)
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func stopSharing() async {
        do {
            _ = try await server.unshareSession(directory: session.directory, sessionID: session.id)
            shareURL = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Revert the session to before `messageID`. The reverted state arrives over
    /// the SSE stream (session.updated with the revert boundary).
    private func revert(_ messageID: String) async {
        try? await server.revertSession(directory: session.directory, sessionID: session.id, messageID: messageID)
    }

    /// Runs a slash command; the expansion + reply stream back over the SSE.
    private func runCommand(_ command: CommandInfo) {
        Task {
            try? await server.runCommand(directory: session.directory, sessionID: session.id,
                                         command: command.name)
        }
    }

    /// Restore all reverted messages.
    private func restore() async {
        do {
            try await server.unrevertSession(directory: session.directory, sessionID: session.id)
            store.setRevert(nil)
        } catch {}
    }

    /// Reads a picked repo file and stages it as a context part on the composer.
    /// `/file/content` wants a path relative to `directory`, so use its folder.
    private func attachFile(_ entry: FileEntry) {
        let parent = (entry.absolute as NSString).deletingLastPathComponent
        let id = UUID()
        fileAttachments.append(FileAttachment(id: id, filename: entry.name, part: [
            "type": "file", "mime": "text/plain",
            "url": "file://\(entry.absolute)", "filename": entry.name,
        ]))
        Task { @MainActor in
            guard let content = try? await server.readFile(directory: parent, path: entry.name),
                  !content.isEmpty,
                  let index = fileAttachments.firstIndex(where: { $0.id == id }) else { return }
            fileAttachments[index].part["source"] = [
                "type": "file", "path": entry.absolute,
                "text": ["value": content, "start": 0, "end": content.count],
            ]
        }
    }
}

/// A shareable URL wrapped for `.sheet(item:)`.
struct ShareURL: Identifiable {
    let id = UUID()
    let url: URL
}

/// Bridges `UIActivityViewController` (the system share sheet) into SwiftUI.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Small live/connecting/reconnecting indicator shown in the nav bar.
private struct StreamStatusBadge: View {
    let status: SessionStore.StreamStatus

    var body: some View {
        switch status {
        case .idle:
            EmptyView()
        case .live:
            HStack(spacing: 4) {
                Circle().fill(.green).frame(width: 7, height: 7)
                Text("Live").font(.caption2).foregroundStyle(.secondary)
            }
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
