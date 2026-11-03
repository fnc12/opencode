import Foundation

/// Hands a StoreKit-signed transaction (JWS) to the relay, which verifies it with
/// Apple and binds the subscription to the account that owns the paired tunnel.
///
/// Contract: `POST <relay>/api/iap/apple`, `X-Tunnel-Token` like `/api/devices`,
/// body `{"tunnelId": …, "signedTransaction": <JWS>}`; a 2xx answer carries
/// `{"entitledUntil": <unix seconds>, "active": bool}` (`entitledUntil` is 0 when
/// the subscription is expired or revoked).
struct RelayIAPClient: Sendable {
    let relayURL: String
    let tunnelID: String
    let token: String
    var session: URLSession = .shared

    /// The relay target for a connection, or nil outside relay mode (a direct
    /// connection has no relay account to bind the purchase to).
    init?(config: ConnectionConfig, session: URLSession = .shared) {
        guard config.mode == .relay, !config.relayURL.isEmpty,
              !config.tunnelID.isEmpty, !config.token.isEmpty else { return nil }
        self.init(relayURL: config.relayURL, tunnelID: config.tunnelID, token: config.token, session: session)
    }

    init(relayURL: String, tunnelID: String, token: String, session: URLSession = .shared) {
        self.relayURL = relayURL
        self.tunnelID = tunnelID
        self.token = token
        self.session = session
    }

    /// The upload request. Pure, so its shape is unit-testable.
    func request(signedTransaction jws: String) throws -> URLRequest {
        guard let url = URL(string: relayURL.trimmedSlashes + "/api/iap/apple") else {
            throw ClientError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "X-Tunnel-Token")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "tunnelId": tunnelID, "signedTransaction": jws,
        ])
        return request
    }

    /// Uploads the JWS; returns the entitlement expiry the relay reports, if any.
    func upload(signedTransaction jws: String) async throws -> Date? {
        let (data, response) = try await session.data(for: request(signedTransaction: jws))
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw ClientError.http((response as? HTTPURLResponse)?.statusCode ?? 0)
        }
        struct Reply: Decodable { let entitledUntil: Int64? }
        guard let until = (try? JSONDecoder().decode(Reply.self, from: data))?.entitledUntil, until > 0 else {
            return nil
        }
        return Date(timeIntervalSince1970: TimeInterval(until))
    }
}
