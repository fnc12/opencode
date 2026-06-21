import Foundation

enum MessageInfo: Decodable, Identifiable {
    case user(UserMessage)
    case assistant(AssistantMessage)

    var id: String {
        switch self {
        case .user(let m): m.id
        case .assistant(let m): m.id
        }
    }

    var role: String {
        switch self {
        case .user: "user"
        case .assistant: "assistant"
        }
    }

    var sessionID: String {
        switch self {
        case .user(let m): m.sessionID
        case .assistant(let m): m.sessionID
        }
    }

    private enum CodingKeys: String, CodingKey {
        case role
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let role = try container.decode(String.self, forKey: .role)
        switch role {
        case "user":
            self = .user(try UserMessage(from: decoder))
        case "assistant":
            self = .assistant(try AssistantMessage(from: decoder))
        default:
            throw DecodingError.dataCorrupted(.init(
                codingPath: [CodingKeys.role],
                debugDescription: "Unknown role: \(role)"
            ))
        }
    }
}
