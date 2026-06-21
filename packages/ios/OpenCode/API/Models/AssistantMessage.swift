import Foundation

struct AssistantMessage: Decodable, Identifiable {
    let id: String
    let sessionID: String
    let role: String
    let time: AssistantMessageTime
    let modelID: String
    let providerID: String
    let agent: String
    let cost: Double
    let tokens: AssistantTokens
    let error: MessageError?
}

struct AssistantMessageTime: Decodable {
    let created: Double
    let completed: Double?
}

struct AssistantTokens: Decodable {
    let input: Int
    let output: Int
    let reasoning: Int
    let cache: AssistantTokensCache
}

struct AssistantTokensCache: Decodable {
    let read: Int
    let write: Int
}
