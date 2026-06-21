import SwiftUI

@main
struct OpenCodeApp: App {
    @State private var server = ServerConnection()

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
                // Auto-connect if a complete config was restored from the Keychain.
                if !server.connected && server.config.isComplete {
                    await server.connect()
                }
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
