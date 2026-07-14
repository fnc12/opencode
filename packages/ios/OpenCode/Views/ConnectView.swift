import SwiftUI

struct ConnectView: View {
    @Bindable var server: ServerConnection
    @State private var showScanner = false
    @State private var pasteText = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 28) {
                Spacer()

                VStack(spacing: 12) {
                    Text("shubat")
                        .font(.system(size: 36, weight: .bold, design: .monospaced))
                    Text("for OpenCode")
                        .font(.system(size: 18, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                }

                Picker("Mode", selection: $server.config.mode) {
                    Text("Relay").tag(ConnectionMode.relay)
                    Text("Direct").tag(ConnectionMode.direct)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 32)
                // Lock the mode switch, every field, the QR scan button and the
                // paste box while a connect attempt is in flight — the config
                // being validated must not change under it.
                .disabled(server.loading)

                Group {
                    switch server.config.mode {
                    case .relay: relayFields
                    case .direct: directFields
                    }
                }
                .padding(.horizontal, 32)
                .disabled(server.loading)

                connectButton

                if server.loading {
                    // Escape hatch: cancel the request cleanly instead of waiting
                    // out the timeout (or force-killing the app).
                    Button("Cancel") { server.cancelConnect() }
                        .padding(.horizontal, 32)
                        .accessibilityIdentifier("connect.cancel")
                }

                if let error = server.error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()
                Spacer()
            }
            .navigationTitle("")
            .sheet(isPresented: $showScanner) {
                QRScannerView { code in
                    if !server.applyPairing(code) {
                        server.error = "Invalid pairing code"
                    }
                }
            }
        }
    }

    @ViewBuilder private var relayFields: some View {
        VStack(spacing: 16) {
            Button {
                showScanner = true
            } label: {
                Label("Scan pairing QR", systemImage: "qrcode.viewfinder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            HStack {
                VStack { Divider() }
                Text("or enter manually").font(.caption).foregroundStyle(.secondary)
                VStack { Divider() }
            }

            field("Relay URL", text: $server.config.relayURL, keyboard: .URL)
            field("Tunnel ID", text: $server.config.tunnelID)
            SecureField("Token", text: $server.config.token)
                .textFieldStyle(.roundedBorder)
                .font(.system(.body, design: .monospaced))

            pasteField
        }
    }

    @ViewBuilder private var directFields: some View {
        VStack(spacing: 16) {
            field("Server URL", text: $server.config.directURL, keyboard: .URL)
                .accessibilityIdentifier("connect.serverURL")
            SecureField("Password (optional)", text: Binding(
                get: { server.config.password ?? "" },
                set: { server.config.password = $0.isEmpty ? nil : $0 }
            ))
            .textFieldStyle(.roundedBorder)
        }
    }

    /// Lets the user paste a full `opencode://pair?…` string instead of typing fields.
    @ViewBuilder private var pasteField: some View {
        HStack {
            TextField("Paste pairing link", text: $pasteText)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(.footnote, design: .monospaced))
            Button("Apply") {
                if server.applyPairing(pasteText) {
                    pasteText = ""
                    server.error = nil
                } else {
                    server.error = "Invalid pairing link"
                }
            }
            .disabled(pasteText.isEmpty)
        }
    }

    private var connectButton: some View {
        Button {
            // Dismiss the keyboard: once the spinner shows there's nothing left
            // to type, and the fields shouldn't sit behind the keyboard.
            UIApplication.shared.sendAction(
                #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            // Go through startConnect() so the request is owned by connectTask —
            // otherwise the Cancel button has nothing to cancel and a hung connect
            // (e.g. a stalled tunnel) can only be escaped by killing the app.
            server.startConnect()
        } label: {
            if server.loading {
                ProgressView().tint(.white).frame(maxWidth: .infinity)
            } else {
                Text("Connect").fontWeight(.semibold).frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.borderedProminent)
        .tint(.blue)
        .padding(.horizontal, 32)
        .disabled(server.loading || !server.config.isComplete)
        .accessibilityIdentifier("connect.button")
    }

    private func field(_ title: String, text: Binding<String>, keyboard: UIKeyboardType = .default) -> some View {
        TextField(title, text: text)
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(keyboard)
            .font(.system(.body, design: .monospaced))
    }
}
