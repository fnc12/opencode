import SwiftUI
import UIKit
import PhotosUI

struct SessionView: View {
    let session: Session
    var server: ServerConnection

    @State private var store = SessionStore()
    @State private var loading = true
    @State private var error: String?
    // Scroll-up pagination cursor state. The initial load pulls only the newest
    // page (see `initialPageSize`) so the screen fills instantly even on sessions
    // whose full history is tens of MB; older pages stream in as the user scrolls
    // up. `oldestCursor` is the `before=` token for the next older page; nil +
    // reachedStart means we've hit the very first message.
    @State private var oldestCursor: String?
    @State private var reachedStart = false
    @State private var loadingOlder = false
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
    /// Whether the session has any file changes — the diff toolbar button only
    /// appears when there is something to show (the top bar is tight on space).
    @State private var hasDiff = false
    @AppStorage("composer.providerID") private var providerID = ""
    @AppStorage("composer.modelID") private var modelID = ""

    var body: some View {
        ZStack {
            if let error {
                // A dead-end on a flaky network is the worst place to strand the
                // user — the initial fetch times out on 4G and there's no way back
                // in except leaving the screen. Offer an in-place Retry.
                ContentUnavailableView {
                    Label("Error", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(error)
                } actions: {
                    Button {
                        Task { await retry() }
                    } label: {
                        Label("Retry", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("session.retry")
                }
            } else {
                // The whole screen is UIKit (message list + bottom bar) so the
                // keyboard is handled natively: the composer is the controller's
                // inputAccessoryView. Ignore SwiftUI's keyboard avoidance here.
                SessionContent(messages: visibleMessages,
                               revision: store.revision,
                               onRevert: { revertTarget = $0 },
                               onLoadOlder: { Task { await loadOlder() } },
                               pendingQuestions: store.pendingQuestions,
                               onQuestionReply: { request, answers in Task { await handleQuestionReply(request, answers) } },
                               onQuestionReject: { request in Task { await handleQuestionReject(request) } },
                               showTyping: showThinking && !isStuck,
                               barRevision: barRevision) {
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
                if loading && store.messages.isEmpty {
                    // A shimmering placeholder transcript reads better than a bare
                    // spinner while the newest page loads. Only on a cold open —
                    // once any message is present (incl. a cache hit, later) the
                    // real list shows through instead.
                    MessageSkeletonView()
                        .background(Color(.systemBackground))
                        .transition(.opacity)
                }
            }
        }
        .navigationTitle(session.title.isEmpty ? "Untitled" : session.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { hasDiff = (session.summary?.files ?? 0) > 0; await refreshDiffBadge() }
        .onChange(of: store.isBusy) { _, busy in
            if !busy { Task { await refreshDiffBadge() } } // a finished turn may have edited files
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Equatable island: the session body re-evaluates on every
                // streamed delta, and re-created toolbar buttons visibly flash
                // (measured on device: each re-created toolbar item's platter
                // fades in from alpha 0 over ~0.3s). `.equatable()` skips this
                // subtree's body unless its VALUE inputs actually change. The
                // stream-status badge deliberately lives in its OWN item below:
                // while the stream reconnects the status flips every backoff
                // cycle, and having it as an input here re-created the buttons
                // on every flip — the "buttons constantly blink" bug.
                SessionToolbar(
                    hasShareURL: shareURL != nil,
                    hasDiff: hasDiff,
                    onShell: { showShell = true },
                    onDiff: { showDiff = true },
                    onShare: { Task { await share() } },
                    onStopShare: { Task { await stopSharing() } })
                    .equatable()
            }
            ToolbarItem(placement: .topBarTrailing) {
                StreamStatusBadge(status: store.status)
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
        // Keyed on foregroundNonce: when the app returns from the background this
        // task is torn down and re-run, so we re-fetch messages/permissions
        // (clearing any stale "typing" state) and reconnect a fresh SSE stream
        // instead of waiting on the zombie socket the OS left suspended.
        .task(id: server.foregroundNonce) {
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

    /// A fingerprint of everything the bottom bar (docks + composer) renders from.
    /// Deliberately excludes the streamed message text/parts, so a fast stream
    /// doesn't rebuild the accessory on every delta (which flickered its height
    /// and shoved content under the composer). Changes only when a dock/picker
    /// toggles or busy/stuck flips.
    private var runningTools: [RunningTool] { RunningTools.extract(store.messages) }

    private var barRevision: Int {
        var h = Hasher()
        h.combine(runningTools.map(\.id))
        h.combine(isStreaming)
        h.combine(isStuck)
        h.combine(store.revertMessageID)
        h.combine(store.todos.count)
        h.combine(store.pendingPermissions.map(\.id))
        // Questions are NOT in the accessory anymore (they're the list footer), so
        // a new/answered question doesn't need to rebuild the composer bar.
        h.combine(fileAttachments.count)
        h.combine(providers.count)
        h.combine(commands.count)
        h.combine(showModelPicker)
        h.combine(showCommands)
        h.combine(showFilePicker)
        h.combine(showPhotoPicker)
        h.combine(pickerItems.count)
        return h.finalize()
    }

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
            // NOTE: the question dock is NOT here — it rides the transcript as the
            // list footer (see `SessionContent.pendingQuestions` / `SessionFooter`),
            // so scrolling up moves it away with the content instead of the
            // messages sliding under a fixed dock and overlapping it.
            if !runningTools.isEmpty {
                // "Background processes" strip (like Claude's): long tools emit
                // no chat text for minutes, and busy-with-no-output otherwise
                // reads as a hang. Absent entirely when nothing runs.
                RunningToolsPill(tools: runningTools)
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

    /// Newest page fetched on open — kept small so a huge session (tens of MB of
    /// tool output) opens instantly instead of blocking on the whole transcript.
    private static let initialPageSize = 5
    /// Older pages pulled on scroll-up. Larger than the initial page: by the time
    /// the user scrolls back they want a chunk of history, and it's one round-trip.
    private static let olderPageSize = 20

    /// Pages in the next older chunk of history (scroll-up). Guarded so the many
    /// scroll events near the top collapse into one in-flight request; a nil
    /// `oldestCursor` / `reachedStart` means there's nothing older to fetch.
    private func loadOlder() async {
        guard !loadingOlder, !reachedStart, let cursor = oldestCursor else { return }
        loadingOlder = true
        defer { loadingOlder = false }
        do {
            let page = try await server.messagesPage(
                directory: session.directory, sessionID: session.id,
                limit: Self.olderPageSize, before: cursor)
            store.prependOlder(page.messages)
            oldestCursor = page.nextCursor
            if page.nextCursor == nil { reachedStart = true }
        } catch {
            // Transient (e.g. a flaky relay hop): leave the cursor untouched so the
            // next scroll near the top retries. No user-facing error for a page we
            // fetch speculatively ahead of the user reaching it.
        }
    }

    /// Re-checks whether the session has any file changes (drives the diff
    /// toolbar button's visibility). Cheap: one `GET /session/:id`, reading the
    /// server-maintained summary — not the diff payload itself.
    private func refreshDiffBadge() async {
        guard let fresh = try? await server.getSession(id: session.id) else { return }
        hasDiff = (fresh.summary?.files ?? 0) > 0
    }

    /// Clears the error screen and re-runs the load + stream. Wired to the Retry
    /// button so a timed-out initial fetch isn't a dead end.
    private func retry() async {
        error = nil
        loading = true
        await run()
    }

    /// Loads the message history, then consumes the SSE stream, reconnecting
    /// with exponential backoff until the view (and thus this task) goes away.
    private func run() async {
        // Cache-first paint: show the last-seen newest page from disk immediately,
        // then refresh from the network in parallel below. On a cache hit the
        // skeleton is skipped (messages are non-empty) so a reopen feels instant
        // even on a slow hop.
        if store.messages.isEmpty, let cached = MessageCache.load(session.id) {
            store.setInitial(cached)
            store.setRevert(session.revert?.messageID)
            loading = false
        }
        // Kick the auxiliary fetches off CONCURRENTLY with the message page — a
        // question already pending for this session used to wait behind messages AND
        // permissions (three serial round-trips), so on open the transcript showed
        // and the dock only popped in seconds later. They're independent; run them
        // in parallel and apply each as it lands.
        async let permsFetch = server.permissions(directory: session.directory)
        async let questionsFetch = server.questions(directory: session.directory)
        async let todosFetch = server.sessionTodos(directory: session.directory, sessionID: session.id)

        do {
            // Only the newest page — not the whole transcript. A single session can
            // carry tens of MB of tool output; fetching it all blocked the screen
            // for 40s–2min. The newest page renders instantly and older history
            // pages in on scroll-up. (Measured: newest-5 = 32KB/4ms vs the full
            // history = 41MB on the pathological session.)
            let page = try await server.messagesPage(
                directory: session.directory, sessionID: session.id, limit: Self.initialPageSize)
            store.setInitial(page.messages)
            oldestCursor = page.nextCursor
            reachedStart = page.nextCursor == nil
            store.setRevert(session.revert?.messageID)
            MessageCache.save(session.id, raw: page.raw) // seed the next reopen
        } catch is CancellationError {
            loading = false // the .task was cancelled (e.g. a screen presented over us) — not an error
            return
        } catch let e as URLError where e.code == .cancelled {
            loading = false
            return
        } catch {
            // Keep any cache-painted messages on screen; only dead-end to the error
            // view when there's nothing to show.
            if store.messages.isEmpty { self.error = error.localizedDescription }
            loading = false
            return
        }
        loading = false

        // Apply the already-in-flight seeds. Questions first — that's the one the
        // user is waiting to answer.
        if let pendingQuestions = try? await questionsFetch {
            store.setInitialQuestions(pendingQuestions.filter { $0.sessionID == session.id })
        }
        if let pending = try? await permsFetch {
            store.setInitialPermissions(pending.filter { $0.sessionID == session.id })
        }
        if let todos = try? await todosFetch {
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
        // UI tests can inject a real captured question payload (raw /question JSON
        // array) to reproduce layout bugs with production data.
        if let raw = ProcessInfo.processInfo.environment["UITEST_QUESTION_JSON"],
           let decoded = try? JSONDecoder().decode([QuestionRequest].self, from: Data(raw.utf8)),
           let first = decoded.first {
            store.setInitialQuestions([QuestionRequest(id: first.id, sessionID: session.id, questions: first.questions)])
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
            // Just the dot — the "Live" label crowded the toolbar and truncated
            // to "Li…" against the edge. The green dot alone reads as "connected".
            Circle().fill(.green).frame(width: 8, height: 8)
                .accessibilityLabel("Live")
                .accessibilityIdentifier("session.live")
        case .connecting, .reconnecting:
            // A spinner alone (no text) for the same reason — transient anyway.
            ProgressView().controlSize(.mini)
                .accessibilityLabel(status == .connecting ? "Connecting" : "Reconnecting")
                .accessibilityIdentifier("session.status")
        }
    }
}

/// The session toolbar's trailing buttons, isolated behind `Equatable` so the
/// per-delta re-evaluation of the session body can't re-create them: SwiftUI
/// skips this body unless the share state or stream status changes, which keeps
/// the shell/diff/share buttons from flashing on device while a reply streams.
/// The action closures are intentionally excluded from equality — they capture
/// the same state setters every time.
private struct SessionToolbar: View, Equatable {
    let hasShareURL: Bool
    /// The diff button only shows when the session actually changed files —
    /// the top bar is tight, don't spend a slot on an empty screen.
    let hasDiff: Bool
    let onShell: () -> Void
    let onDiff: () -> Void
    let onShare: () -> Void
    let onStopShare: () -> Void

    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.hasShareURL == rhs.hasShareURL && lhs.hasDiff == rhs.hasDiff
    }

    var body: some View {
        HStack(spacing: 10) {
            // Stop lives in the composer (send ↔ stop), like the web — not here.
            Button(action: onShell) {
                Image(systemName: "terminal")
            }
            .accessibilityIdentifier("session.shell")
            .accessibilityLabel("Shell")
            if hasDiff {
                Button(action: onDiff) {
                    Image(systemName: "plusminus")
                }
                .accessibilityIdentifier("session.diff")
                .accessibilityLabel("Changes")
            }
            Menu {
                Button(action: onShare) {
                    Label("Share link", systemImage: "square.and.arrow.up")
                }
                if hasShareURL {
                    Button(role: .destructive, action: onStopShare) {
                        Label("Stop sharing", systemImage: "xmark.circle")
                    }
                }
            } label: {
                Image(systemName: hasShareURL ? "link.circle.fill" : "square.and.arrow.up")
            }
            .accessibilityIdentifier("session.share")
        }
    }
}
