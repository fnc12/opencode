import Foundation

/// How the app reaches the OpenCode server.
enum ConnectionMode: String, Codable, Sendable {
    /// Through the OpenCode Remote relay (server is behind NAT).
    case relay
    /// Straight to a reachable OpenCode server (LAN / dev).
    case direct
}

/// Everything needed to connect, persisted in the Keychain (it holds a token).
struct ConnectionConfig: Codable, Equatable, Sendable {
    var mode: ConnectionMode = .relay

    // relay mode
    var relayURL = ""
    var tunnelID = ""
    var token = ""

    // direct mode
    var directURL = ""
    var password: String?

    /// The base URL the API client targets. For relay mode this is the
    /// per-tunnel proxy path on the relay; for direct mode it's the server URL.
    var baseURL: String {
        switch mode {
        case .relay:
            return relayURL.trimmedSlashes + "/t/" + tunnelID
        case .direct:
            return directURL.trimmedSlashes
        }
    }

    /// Whether the user has filled in enough to attempt a connection.
    var isComplete: Bool {
        switch mode {
        case .relay:
            return !relayURL.isEmpty && !tunnelID.isEmpty && !token.isEmpty
        case .direct:
            return !directURL.isEmpty
        }
    }

    /// Parses a pairing payload produced by the connector, accepting either an
    /// `opencode://pair?relay=…&tunnel=…&token=…` URL (also used for QR codes
    /// and deep links) or a bare `relay=…&tunnel=…&token=…` query string.
    static func fromPairing(_ raw: String) -> ConnectionConfig? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        var query = trimmed
        if let comps = URLComponents(string: trimmed), comps.scheme != nil {
            guard comps.host == "pair" || comps.path.contains("pair") else { return nil }
            query = comps.percentEncodedQuery ?? ""
        }

        var items: [String: String] = [:]
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            guard kv.count == 2 else { continue }
            let key = String(kv[0])
            let value = String(kv[1]).removingPercentEncoding ?? String(kv[1])
            items[key] = value
        }

        guard let relay = items["relay"], let tunnel = items["tunnel"], let token = items["token"],
              !relay.isEmpty, !tunnel.isEmpty, !token.isEmpty
        else { return nil }

        return ConnectionConfig(mode: .relay, relayURL: relay, tunnelID: tunnel, token: token)
    }
}

extension String {
    /// Trims surrounding whitespace and any trailing slashes.
    var trimmedSlashes: String {
        var s = trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
}
