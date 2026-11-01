import XCTest
@testable import OpenCode

/// The JWS hand-off to the relay (`POST /api/iap/apple`): request shape, auth
/// header, and how replies are read.
final class RelayIAPClientTests: XCTestCase {
    override func setUp() { super.setUp(); StubURLProtocol.reset() }

    private func relayConfig() -> ConnectionConfig {
        var cfg = ConnectionConfig()
        cfg.mode = .relay
        cfg.relayURL = "https://relay.stub/"
        cfg.tunnelID = "tun_abc"
        cfg.token = "secret"
        return cfg
    }

    func testOnlyRelayModeHasAClient() {
        XCTAssertNotNil(RelayIAPClient(config: relayConfig()))
        var direct = relayConfig()
        direct.mode = .direct
        XCTAssertNil(RelayIAPClient(config: direct))
        var noToken = relayConfig()
        noToken.token = ""
        XCTAssertNil(RelayIAPClient(config: noToken))
    }

    func testRequestShape() throws {
        let client = try XCTUnwrap(RelayIAPClient(config: relayConfig()))
        let request = try client.request(signedTransaction: "jws.payload.sig")
        XCTAssertEqual(request.url?.absoluteString, "https://relay.stub/api/iap/apple")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Tunnel-Token"), "secret")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String])
        XCTAssertEqual(body, ["tunnelId": "tun_abc", "signedTransaction": "jws.payload.sig"])
    }

    func testUploadReadsEntitledUntil() async throws {
        StubURLProtocol.enqueue(200, body: #"{"entitledUntil":1800000000}"#)
        let client = try XCTUnwrap(RelayIAPClient(config: relayConfig(), session: StubURLProtocol.session()))
        let until = try await client.upload(signedTransaction: "jws")
        XCTAssertEqual(until, Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(StubURLProtocol.seen.first?.url?.path, "/api/iap/apple")
    }

    func testUploadWithoutExpiryReturnsNil() async throws {
        StubURLProtocol.enqueue(204)
        let client = try XCTUnwrap(RelayIAPClient(config: relayConfig(), session: StubURLProtocol.session()))
        let until = try await client.upload(signedTransaction: "jws")
        XCTAssertNil(until)
    }

    func testUploadRejectedThrows() async throws {
        StubURLProtocol.enqueue(401, body: "bad token")
        let client = try XCTUnwrap(RelayIAPClient(config: relayConfig(), session: StubURLProtocol.session()))
        do {
            _ = try await client.upload(signedTransaction: "jws")
            XCTFail("expected an error")
        } catch ClientError.http(let code) {
            XCTAssertEqual(code, 401)
        }
    }
}
