import XCTest
import SwiftUI
import SnapshotTesting
@testable import OpenCode

/// ConnectView states (in-scope screen, snapshot-covered): relay vs direct mode,
/// and the error state. Driven by a ServerConnection with the right config.
@MainActor
final class ConnectViewSnapshotTests: XCTestCase {
    private let dark = UITraitCollection(userInterfaceStyle: .dark)

    private func server(mode: ConnectionMode, error: String? = nil, loading: Bool = false) -> ServerConnection {
        var cfg = ConnectionConfig()
        cfg.mode = mode
        cfg.directURL = "http://host:4096"
        cfg.relayURL = "https://relay.example"
        cfg.tunnelID = "tun_1"
        cfg.token = "tok_1"
        let c = ServerConnection(config: cfg)
        c.error = error
        c.loading = loading
        return c
    }

    private func image() -> Snapshotting<ConnectView, UIImage> {
        .image(layout: .device(config: .iPhone13), traits: dark)
    }

    func testRelayMode() {
        assertSnapshot(of: ConnectView(server: server(mode: .relay)), as: image())
    }
    func testDirectMode() {
        assertSnapshot(of: ConnectView(server: server(mode: .direct)), as: image())
    }
    func testErrorState() {
        assertSnapshot(of: ConnectView(server: server(mode: .direct, error: "Connection refused")), as: image())
    }
}
