import Foundation

/// `/config/providers` response. We decode only what the model picker needs —
/// notably NOT the provider `key` field (an API secret that must never be
/// stored or surfaced by the client).
struct ProvidersResponse: Decodable {
    let providers: [ProviderInfo]
}

struct ProviderInfo: Decodable, Identifiable {
    let id: String
    let name: String?
    let models: [String: ModelInfo]

    private enum CodingKeys: String, CodingKey { case id, name, models }
}

struct ModelInfo: Decodable, Identifiable {
    let id: String
    let name: String?

    var displayName: String { name ?? id }

    private enum CodingKeys: String, CodingKey { case id, name }
}
