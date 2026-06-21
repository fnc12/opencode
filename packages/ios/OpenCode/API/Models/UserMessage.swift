import Foundation

struct UserMessage: Decodable, Identifiable {
    let id: String
    let sessionID: String
    let role: String
    let time: UserMessageTime
    let agent: String?
    let model: UserMessageModel?
}

struct UserMessageTime: Decodable {
    let created: Double
}

struct UserMessageModel: Decodable {
    let providerID: String
    let modelID: String
}
