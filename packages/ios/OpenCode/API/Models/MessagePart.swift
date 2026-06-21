import Foundation

struct MessagePart: Decodable, Identifiable {
    let id: String
    let sessionID: String
    let messageID: String
    let type: String
    let content: PartContent?

    private enum CodingKeys: String, CodingKey {
        case id, sessionID, messageID, type
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        messageID = try container.decode(String.self, forKey: .messageID)
        type = try container.decode(String.self, forKey: .type)
        content = try? PartContent(from: decoder, type: type)
    }
}
