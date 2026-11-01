import SwiftUI

@main
struct OpenCodeApp: App {
    @State private var server = ServerConnection()
    @State private var subscriptions = SubscriptionStore()
    @UIApplicationDelegateAdaptor(PushManager.self) private var pushManager
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if server.connected {
                    ProjectListView(server: server, subscriptions: subscriptions)
                } else {
                    ConnectView(server: server)
                }
            }
            .task {
                // Renewals and Ask to Buy approvals arrive outside the paywall.
                subscriptions.startListening()
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
                // Link the App Store subscription to the paired relay account.
                await subscriptions.setRelay(RelayIAPClient(config: server.config))
                await subscriptions.refreshEntitlements()
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
            .onChange(of: server.connected) { _, connected in
                // A new pairing (or a disconnect) changes which account a purchase binds to.
                let relay = connected ? RelayIAPClient(config: server.config) : nil
                Task { await subscriptions.setRelay(relay) }
            }
            .onChange(of: scenePhase) { _, phase in
                // Returning to the foreground: nudge views to re-fetch and
                // reconnect their SSE streams (a backgrounded socket goes stale).
                if phase == .active { server.foregroundNonce += 1 }
            }
        }
    }
}
