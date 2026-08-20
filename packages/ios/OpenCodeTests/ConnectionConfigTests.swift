import XCTest
@testable import OpenCode

/// The pairing-link parser + connection config derivations (pure logic, all
/// branches): baseURL per mode, isComplete gating, and fromPairing accepting the
/// opencode:// deep link, a bare query, and rejecting malformed input.
final class ConnectionConfigTests: XCTestCase {
    func testRelayBaseURL() {
        var c = ConnectionConfig()
        c.mode = .relay; c.relayURL = "https://r.example/"; c.tunnelID = "tun_1"
        XCTAssertEqual(c.baseURL, "https://r.example/t/tun_1")
    }

    func testDirectBaseURLTrimsSlashes() {
        var c = ConnectionConfig()
        c.mode = .direct; c.directURL = "http://host:4096//"
        XCTAssertEqual(c.baseURL, "http://host:4096")
    }

    func testIsCompleteRelay() {
        var c = ConnectionConfig(); c.mode = .relay
        XCTAssertFalse(c.isComplete)
        c.relayURL = "r"; c.tunnelID = "t"; c.token = "k"
        XCTAssertTrue(c.isComplete)
    }

    func testIsCompleteDirect() {
        var c = ConnectionConfig(); c.mode = .direct
        XCTAssertFalse(c.isComplete)
        c.directURL = "http://x"
        XCTAssertTrue(c.isComplete)
    }

    func testFromPairingDeepLink() {
        let c = ConnectionConfig.fromPairing("opencode://pair?relay=https://r.example&tunnel=tun_1&token=tok_1")
        XCTAssertEqual(c?.mode, .relay)
        XCTAssertEqual(c?.relayURL, "https://r.example")
        XCTAssertEqual(c?.tunnelID, "tun_1")
        XCTAssertEqual(c?.token, "tok_1")
    }

    func testFromPairingBareQuery() {
        let c = ConnectionConfig.fromPairing("relay=https://r.example&tunnel=tun_2&token=tok_2")
        XCTAssertEqual(c?.tunnelID, "tun_2")
    }

    func testFromPairingPercentDecodes() {
        let c = ConnectionConfig.fromPairing("relay=https%3A%2F%2Fr.example&tunnel=t&token=k")
        XCTAssertEqual(c?.relayURL, "https://r.example")
    }

    func testFromPairingRejectsEmpty() {
        XCTAssertNil(ConnectionConfig.fromPairing("   "))
    }

    func testFromPairingRejectsMissingFields() {
        XCTAssertNil(ConnectionConfig.fromPairing("relay=r&tunnel=t"), "missing token → nil")
        XCTAssertNil(ConnectionConfig.fromPairing("relay=&tunnel=t&token=k"), "empty relay → nil")
    }

    func testFromPairingRejectsNonPairScheme() {
        XCTAssertNil(ConnectionConfig.fromPairing("https://example.com?relay=r&tunnel=t&token=k"),
                     "a non-pair URL scheme must be rejected")
    }
}
