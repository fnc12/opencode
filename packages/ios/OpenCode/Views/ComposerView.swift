import SwiftUI

/// Bottom input bar for a session: pick a model, type (keyboard dictation works
/// out of the box via the mic key), and send. The reply streams back through the
/// existing event stream, so sending is fire-and-forget from the UI's side.
struct ComposerView: View {
    var server: ServerConnection
    let session: Session

    @State private var text = ""
    @State private var sending = false
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
            HStack(alignment: .bottom, spacing: 8) {
                Button { showModelPicker = true } label: {
                    HStack(spacing: 3) {
                        Image(systemName: "cpu")
                        Text(modelLabel).lineLimit(1)
                    }
                    .font(.caption2)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                TextField("Message", text: $text, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)
                    .disabled(sending)
                    .accessibilityIdentifier("composer.field")

                Button { Task { await send() } } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                        .symbolEffect(.pulse, isActive: sending)
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
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !sending && !modelID.isEmpty
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

    private func send() async {
        let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty, !modelID.isEmpty else { return }
        text = "" // optimistic; the user message echoes back over the stream
        sending = true
        sendError = nil
        do {
            try await server.sendPrompt(directory: session.directory, sessionID: session.id,
                                        text: prompt, providerID: providerID, modelID: modelID)
        } catch {
            sendError = error.localizedDescription
            text = prompt // restore so the user doesn't lose what they typed
        }
        sending = false
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
