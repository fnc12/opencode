import SwiftUI

/// Bottom input bar for a session: pick a model, type (keyboard dictation works
/// out of the box via the mic key), and send. The reply streams back through the
/// existing event stream, so sending is fire-and-forget from the UI's side.
struct ComposerView: View {
    var server: ServerConnection
    let session: Session

    @State private var text = ""
    @State private var sendError: String?
    @State private var providers: [ProviderInfo] = []
    @State private var showModelPicker = false

    // Last-used model, remembered across sessions/launches.
    @AppStorage("composer.providerID") private var providerID = ""
    @AppStorage("composer.modelID") private var modelID = ""

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
            HStack {
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

            HStack(alignment: .bottom, spacing: 8) {
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
    }

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !modelID.isEmpty
    }

    private var modelLabel: String {
        guard !modelID.isEmpty else { return "Model" }
        if let provider = providers.first(where: { $0.id == providerID }),
           let model = provider.models[modelID] {
            return model.displayName
        }
        return modelID
    }

    private func loadProviders() async {
        guard providers.isEmpty else { return }
        providers = (try? await server.providers()) ?? []
        if modelID.isEmpty { autoSelectDefault() }
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
        guard !prompt.isEmpty, !modelID.isEmpty else { return }
        text = "" // optimistic; the user message echoes back over the stream
        sendError = nil
        let dir = session.directory, sid = session.id, pid = providerID, mid = modelID
        // Fire-and-forget: the reply arrives over the SSE event stream, and the
        // server keeps generating regardless of this POST — so its duration or
        // timeout must never gate the UI. Surface only a genuine rejection (an
        // HTTP error); a transport hiccup (timeout / cancelled / dropped) just
        // means the turn is already under way.
        Task {
            do {
                try await server.sendPrompt(directory: dir, sessionID: sid, text: prompt,
                                            providerID: pid, modelID: mid)
            } catch let e as URLError where [.timedOut, .cancelled, .networkConnectionLost].contains(e.code) {
                // in flight — the reply comes over the stream
            } catch {
                sendError = error.localizedDescription
                if text.isEmpty { text = prompt } // restore only if untouched
            }
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
