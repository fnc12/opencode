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

    init(id: String, sessionID: String, messageID: String, type: String, content: PartContent?) {
        self.id = id
        self.sessionID = sessionID
        self.messageID = messageID
        self.type = type
        self.content = content
    }

    /// Returns a copy with `delta` appended to a streaming text part. Non-text
    /// parts are returned unchanged (their snapshots arrive via `partUpdated`).
    func appendingText(_ delta: String) -> MessagePart {
        guard case .text(let existing)? = content else { return self }
        return MessagePart(id: id, sessionID: sessionID, messageID: messageID,
                           type: type, content: .text(existing + delta))
    }
}
