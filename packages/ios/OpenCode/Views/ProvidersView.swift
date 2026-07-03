import SwiftUI

/// Manage provider credentials: paste an API key to enable a provider, or remove
/// a stored one. OAuth logins (ChatGPT, Copilot) need a browser, so they're best
/// done on desktop — here we surface the API-key providers.
struct ProvidersView: View {
    let server: ServerConnection
    @Environment(\.dismiss) private var dismiss

    @State private var apiProviders: [String] = []
    @State private var configured: Set<String> = []
    @State private var loading = true
    @State private var error: String?
    @State private var keyEntryFor: String?
    @State private var keyText = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(apiProviders, id: \.self) { id in
                        Button {
                            keyEntryFor = id
                            keyText = ""
                        } label: {
                            HStack {
                                Text(displayName(id)).foregroundStyle(.primary)
                                Spacer()
                                if configured.contains(id) {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                                }
                            }
                        }
                        .swipeActions(edge: .trailing) {
                            if configured.contains(id) {
                                Button(role: .destructive) { Task { await remove(id) } } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                    }
                } header: {
                    Text("API key providers")
                } footer: {
                    Text("Tap a provider to paste its API key. A green check means it's configured. OAuth logins (ChatGPT, Copilot) need a browser — set those up on desktop.")
                }
            }
            .overlay {
                if loading { ProgressView() }
                else if let error { ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error)) }
            }
            .navigationTitle("Providers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .alert("API Key", isPresented: Binding(
                get: { keyEntryFor != nil },
                set: { if !$0 { keyEntryFor = nil } })) {
                SecureField("sk-…", text: $keyText)
                Button("Cancel", role: .cancel) { keyEntryFor = nil }
                Button("Save") {
                    if let id = keyEntryFor { Task { await save(id, keyText) } }
                    keyEntryFor = nil
                }
            } message: {
                Text(keyEntryFor.map { "Enter the API key for \(displayName($0))." } ?? "")
            }
        }
        .task { await load() }
    }

    private func displayName(_ id: String) -> String {
        id.split(separator: "-").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    }

    private func load() async {
        loading = true
        error = nil
        do {
            let methods = try await server.providerAuthMethods()
            apiProviders = methods
                .filter { $0.value.contains { $0.type == "api" } }
                .keys.sorted()
            configured = Set((try? await server.providers())?.map(\.id) ?? [])
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    private func save(_ id: String, _ key: String) async {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            try await server.setProviderKey(providerID: id, key: trimmed)
            configured.insert(id)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func remove(_ id: String) async {
        do {
            try await server.removeProviderAuth(providerID: id)
            configured.remove(id)
        } catch {
            self.error = error.localizedDescription
        }
    }
}
