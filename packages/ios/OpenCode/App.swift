import SwiftUI

@main
struct OpenCodeApp: App {
    @State private var server = ServerConnection()
    @UIApplicationDelegateAdaptor(PushManager.self) private var pushManager
    @Environment(\.scenePhase) private var scenePhase

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
                // UI tests inject a pairing link to reach a live session; inert in
                // production (the env var is never set there).
                if !server.connected,
                   let link = ProcessInfo.processInfo.environment["PAIR_LINK"],
                   server.applyPairing(link) {
                    await server.connect()
                }
                // Auto-connect if a complete config was restored from the Keychain.
                if !server.connected && server.config.isComplete {
                    await server.connect()
                }
                // Register this device for push once connected.
                PushManager.onToken = { token in Task { await server.registerPushToken(token) } }
                if let token = PushManager.lastToken { await server.registerPushToken(token) }
                PushManager.requestAndRegister()
                // Route a tapped notification to its session (flushes a tap that
                // cold-launched the app, too).
                PushManager.setOpenSessionHandler { sid in
                    Task { @MainActor in server.pendingOpenSessionID = sid }
                }
            }
            .onOpenURL { url in
                // Handle opencode://pair?relay=…&tunnel=…&token=… deep links.
                if server.applyPairing(url.absoluteString) {
                    Task { await server.connect() }
                }
            }
            .onChange(of: scenePhase) { _, phase in
                // Returning to the foreground: nudge views to re-fetch and
                // reconnect their SSE streams (a backgrounded socket goes stale).
                if phase == .active { server.foregroundNonce += 1 }
            }
        }
    }
}
