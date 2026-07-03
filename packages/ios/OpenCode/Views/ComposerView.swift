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

    @State private var text = ""
    @State private var sendError: String?
    @State private var providers: [ProviderInfo] = []
    @State private var agents: [AgentInfo] = []
    @State private var showModelPicker = false
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var attachments: [Attachment] = []
    @State private var commands: [CommandInfo] = []
    @State private var showCommands = false

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
                PhotosPicker(selection: $pickerItems, maxSelectionCount: 4, matching: .images) {
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

                TextField("Message", text: $text, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)
                    .accessibilityIdentifier("composer.field")

                Button { send() } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                }
                .disabled(!canSend)
                .accessibilityIdentifier("composer.send")
                .accessibilityLabel("Send")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
        .task { await loadProviders() }
        .sheet(isPresented: $showModelPicker) {
            ModelPickerView(providers: providers, providerID: $providerID, modelID: $modelID)
        }
        .sheet(isPresented: $showCommands) {
            CommandPickerView(commands: commands) { runCommand($0) }
        }
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

    private func loadProviders() async {
        if providers.isEmpty { providers = (try? await server.providers()) ?? [] }
        if agents.isEmpty { agents = (try? await server.agents()) ?? [] }
        if commands.isEmpty { commands = (try? await server.commands(directory: session.directory)) ?? [] }
        if modelID.isEmpty { autoSelectDefault() }
    }

    /// Runs a slash command; the expansion + reply stream back over the SSE.
    private func runCommand(_ command: CommandInfo) {
        let dir = session.directory, sid = session.id
        sendError = nil
        Task {
            do {
                try await server.runCommand(directory: dir, sessionID: sid, command: command.name)
            } catch let e as URLError where [.timedOut, .cancelled, .networkConnectionLost].contains(e.code) {
                // in flight — the reply comes over the stream
            } catch {
                sendError = error.localizedDescription
            }
        }
    }

    /// Pick a sensible default if none chosen — prefer a free `opencode` model.
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
