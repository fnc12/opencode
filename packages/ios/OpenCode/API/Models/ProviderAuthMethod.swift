import Foundation

/// One way to authenticate a provider (`GET /provider/auth`) — an API key or an
/// OAuth flow, each with a human label.
struct ProviderAuthMethod: Decodable {
    let type: String     // "api" | "oauth" | "wellknown"
    let label: String
}
