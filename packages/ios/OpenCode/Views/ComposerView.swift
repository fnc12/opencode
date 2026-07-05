import SwiftUI
import PhotosUI

/// Bottom input bar for a session: pick a model, attach images, type (keyboard
/// dictation works out of the box via the mic key), and send. The reply streams
/// back through the existing event stream, so sending is fire-and-forget.
struct ComposerView: View {
    var server: ServerConnection
    let session: Session
    /// File attachments + picker presentation live in `SessionView` (the main
    /// window), because presenting a sheet from this input-accessory-hosted view
    /// tears down the accessory. The composer just renders + toggles them.
    @Binding var fileAttachments: [FileAttachment]
    @Binding var showFilePicker: Bool
    /// Model + command pickers are presented from `SessionView` too — a sheet
    /// presented from this accessory-hosted view tears down the composer. The
    /// composer loads the data into these bindings and toggles the flags.
    @Binding var providers: [ProviderInfo]
    @Binding var commands: [CommandInfo]
    @Binding var showModelPicker: Bool
    @Binding var showCommands: Bool
    @Binding var pickerItems: [PhotosPickerItem]
    @Binding var showPhotoPicker: Bool
    /// The agent is streaming — turns the send button into a stop/abort button.
    var isBusy: Bool = false

    @State private var text = ""
    @State private var sendError: String?
    @State private var agents: [AgentInfo] = []
    @State private var attachments: [Attachment] = []

    /// A picked image staged for the next prompt (thumbnail + its data URL).
    struct Attachment: Identifiable {
        let id = UUID()
        let filename: String
        let mime: String
        let dataURL: String
        let image: UIImage
    }

    // Last-used model + agent, remembered across sessions/launches.
    @AppStorage("composer.providerID") private var providerID = ""
    @AppStorage("composer.modelID") private var modelID = ""
    @AppStorage("composer.agent") private var agentName = "build"

    var body: some View {
        VStack(spacing: 6) {
            if let sendError {
                Text(sendError)
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            // Model selector on its own compact line, so the message field can
            // take the full width of the bar.
            HStack(spacing: 14) {
                Menu {
                    Picker("Agent", selection: $agentName) {
                        ForEach(agentChoices, id: \.self) { name in
                            Text(name.capitalized).tag(name)
                        }
                    }
                } label: {
                    HStack(spacing: 3) {
                        Image(systemName: agentIcon)
                        Text(agentName.capitalized).lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down").font(.system(size: 8))
                    }
                    .font(.caption2)
                }
                .buttonStyle(.borderless)
                .foregroundStyle(agentName == "plan" ? Color.orange : .secondary)
                .accessibilityIdentifier("composer.agent")

                Button { showModelPicker = true } label: {
                    HStack(spacing: 3) {
                        Image(systemName: "cpu")
                        Text(modelLabel).lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down").font(.system(size: 8))
                    }
                    .font(.caption2)
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("composer.model")
                Spacer(minLength: 0)
            }

            if !attachments.isEmpty || !fileAttachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(attachments) { att in
                            ZStack(alignment: .topTrailing) {
                                Image(uiImage: att.image)
                                    .resizable().scaledToFill()
                                    .frame(width: 56, height: 56)
                                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                Button { attachments.removeAll { $0.id == att.id } } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(.white, .black.opacity(0.6))
                                }
                                .padding(2)
                            }
                        }
                        ForEach(fileAttachments) { file in
                            HStack(spacing: 4) {
                                Image(systemName: "doc.text")
                                Text(file.filename).lineLimit(1)
                                Button { fileAttachments.removeAll { $0.id == file.id } } label: {
                                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                                }
                                .accessibilityIdentifier("composer.removeFile")
                            }
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .frame(height: 32)
                            .background(Color(.secondarySystemBackground))
                            .clipShape(Capsule())
                        }
                    }
                }
                .frame(height: 60)
            }

            HStack(alignment: .bottom, spacing: 8) {
                // The photo picker is presented from SessionView (see below) —
                // presenting it here would tear the composer down on dismiss.
                Button { showPhotoPicker = true } label: {
                    Image(systemName: "photo").font(.title3)
                }
                .accessibilityIdentifier("composer.attach")

                Button { showFilePicker = true } label: {
                    Image(systemName: "doc.badge.plus").font(.title3)
                }
                .accessibilityIdentifier("composer.file")

                if !commands.isEmpty {
                    Button { showCommands = true } label: {
                        Image(systemName: "slash.circle").font(.title3)
                    }
                    .accessibilityIdentifier("composer.commands")
                }

                // SwiftUI's TextField(axis: .vertical) renders its text clipped
                // above the field inside the inputAccessoryView, so use a
                // UITextView-backed growing field.
                GrowingTextView(text: $text, placeholder: "Message")
                    .background(Color(uiColor: .secondarySystemBackground),
                                in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(Color(uiColor: .separator), lineWidth: 0.5))
                    .accessibilityIdentifier("composer.field")

                // Send ↔ Stop: while the agent is streaming this same button
                // becomes a stop (■) that aborts — matching the web client, which
                // toggles its composer submit button rather than a separate control.
                Button { isBusy ? stop() : send() } label: {
                    Image(systemName: isBusy ? "stop.circle.fill" : "arrow.up.circle.fill")
                        .font(.title2)
                }
                .disabled(isBusy ? false : !canSend)
                .accessibilityIdentifier("composer.send")
                .accessibilityLabel(isBusy ? "Stop" : "Send")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
        .task { await loadAgents() }
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task { await loadAttachments(items) }
        }
    }

    private var canSend: Bool {
        (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
         || !attachments.isEmpty || !fileAttachments.isEmpty) && !modelID.isEmpty
    }


    /// Decodes picked photos into JPEG data-URL attachments (+ a thumbnail).
    private func loadAttachments(_ items: [PhotosPickerItem]) async {
        var loaded: [Attachment] = []
        for (i, item) in items.enumerated() {
            if let data = try? await item.loadTransferable(type: Data.self),
               let img = UIImage(data: data),
               let jpeg = img.jpegData(compressionQuality: 0.7) {
                loaded.append(Attachment(
                    filename: "image\(i + 1).jpg", mime: "image/jpeg",
                    dataURL: "data:image/jpeg;base64,\(jpeg.base64EncodedString())", image: img))
            }
        }
        attachments = loaded
    }

    private var modelLabel: String {
        guard !modelID.isEmpty else { return "Model" }
        if let provider = providers.first(where: { $0.id == providerID }),
           let model = provider.models[modelID] {
            return model.displayName
        }
        return modelID
    }

    /// Selectable agent names; falls back to build/plan until the list loads.
    private var agentChoices: [String] {
        let loaded = agents.filter(\.selectable).map(\.name)
        return loaded.isEmpty ? ["build", "plan"] : loaded
    }

    private var agentIcon: String {
        switch agentName {
        case "plan": return "list.bullet.rectangle"
        case "build": return "hammer"
        default: return "person"
        }
    }

    // Only agents load here (an inline menu). Providers + commands are loaded by
    // SessionView so their pickers — presented from the main window — see the
    // data (a write from this accessory-hosted view doesn't reach those sheets).
    private func loadAgents() async {
        if agents.isEmpty { agents = (try? await server.agents()) ?? [] }
    }

    /// Abort the in-flight generation for this session.
    private func stop() {
        Task { try? await server.abort(directory: session.directory, sessionID: session.id) }
    }

    private func send() {
        let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty || !attachments.isEmpty || !fileAttachments.isEmpty, !modelID.isEmpty else { return }
        let imageParts: [[String: Any]] = attachments.map {
            ["type": "file", "mime": $0.mime, "filename": $0.filename, "url": $0.dataURL]
        }
        let atts = imageParts + fileAttachments.map { $0.part }
        text = "" // optimistic; the user message echoes back over the stream
        attachments = []
        fileAttachments = []
        pickerItems = []
        sendError = nil
        let dir = session.directory, sid = session.id, pid = providerID, mid = modelID
        let ag = agentName
        // Fire-and-forget: the reply arrives over the SSE event stream, and the
        // server keeps generating regardless of this POST — so its duration or
        // timeout must never gate the UI. Surface only a genuine rejection (an
        // HTTP error); a transport hiccup (timeout / cancelled / dropped) just
        // means the turn is already under way.
        Task {
            do {
                try await server.sendPrompt(directory: dir, sessionID: sid, text: prompt,
                                            providerID: pid, modelID: mid, agent: ag, attachments: atts)
            } catch let e as URLError where [.timedOut, .cancelled, .networkConnectionLost].contains(e.code) {
                // in flight — the reply comes over the stream
            } catch {
                sendError = error.localizedDescription
                if text.isEmpty { text = prompt } // restore only if untouched
            }
        }
    }
}

/// Lists the session's slash commands; tapping one runs it and dismisses.
struct CommandPickerView: View {
    let commands: [CommandInfo]
    let onRun: (CommandInfo) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(commands) { command in
                Button {
                    onRun(command)
                    dismiss()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("/\(command.name)")
                            .font(.body.monospaced())
                            .foregroundStyle(.primary)
                        if let description = command.description, !description.isEmpty {
                            Text(description)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                }
            }
            .navigationTitle("Commands")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Cancel") { dismiss() } } }
        }
    }
}

/// Grouped list of providers → models. Tapping one selects it and dismisses.
struct ModelPickerView: View {
    let providers: [ProviderInfo]
    @Binding var providerID: String
    @Binding var modelID: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(providers) { provider in
                    Section(provider.name ?? provider.id) {
                        ForEach(provider.models.values.sorted { $0.displayName < $1.displayName }) { model in
                            Button {
                                providerID = provider.id
                                modelID = model.id
                                dismiss()
                            } label: {
                                HStack {
                                    Text(model.displayName).foregroundStyle(.primary)
                                    Spacer()
                                    if provider.id == providerID && model.id == modelID {
                                        Image(systemName: "checkmark").foregroundStyle(.tint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Model")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}
