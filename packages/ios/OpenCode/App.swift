import SwiftUI

@main
struct OpenCodeApp: App {
    @State private var server = ServerConnection()
    @UIApplicationDelegateAdaptor(PushManager.self) private var pushManager

    var body: some Scene {
        WindowGroup {
            Group {
                if server.connected {
                    ProjectListView(server: server)
                } else {
                    ConnectView(server: server)
                }
            }
            .task {
                // UI tests start from a clean slate so the connect flow is deterministic.
                if ProcessInfo.processInfo.arguments.contains("UITEST_RESET") {
                    server.forget()
                }
                // Auto-connect if a complete config was restored from the Keychain.
                if !server.connected && server.config.isComplete {
                    await server.connect()
                }
                // Register this device for idle push once connected.
                PushManager.onToken = { token in Task { await server.registerPushToken(token) } }
                if let token = PushManager.lastToken { await server.registerPushToken(token) }
                PushManager.requestAndRegister()
            }
            .onOpenURL { url in
                // Handle opencode://pair?relay=…&tunnel=…&token=… deep links.
                if server.applyPairing(url.absoluteString) {
                    Task { await server.connect() }
                }
            }
        }
    }
}
