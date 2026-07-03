import Foundation

struct MessagePart: Decodable, Identifiable {
    let id: String
    let sessionID: String
    let messageID: String
    let type: String
    let content: PartContent?
    /// A part the model emitted as filler (e.g. a tool-call narration a
    /// non-native-tool model wrote as text) — hidden, like the web client.
    let synthetic: Bool
    /// A part the server marks as not-for-display.
    let ignored: Bool

    /// Whether this part should be shown at all.
    var isVisible: Bool { !synthetic && !ignored }

    private enum CodingKeys: String, CodingKey {
        case id, sessionID, messageID, type, synthetic, ignored
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        messageID = try container.decode(String.self, forKey: .messageID)
        type = try container.decode(String.self, forKey: .type)
        synthetic = (try? container.decode(Bool.self, forKey: .synthetic)) ?? false
        ignored = (try? container.decode(Bool.self, forKey: .ignored)) ?? false
        content = try? PartContent(from: decoder, type: type)
    }

    init(id: String, sessionID: String, messageID: String, type: String, content: PartContent?,
         synthetic: Bool = false, ignored: Bool = false) {
        self.id = id
        self.sessionID = sessionID
        self.messageID = messageID
        self.type = type
        self.content = content
        self.synthetic = synthetic
        self.ignored = ignored
    }

    /// Returns a copy with `delta` appended to a streaming text part. Non-text
    /// parts are returned unchanged (their snapshots arrive via `partUpdated`).
    func appendingText(_ delta: String) -> MessagePart {
        guard case .text(let existing)? = content else { return self }
        return MessagePart(id: id, sessionID: sessionID, messageID: messageID,
                           type: type, content: .text(existing + delta),
                           synthetic: synthetic, ignored: ignored)
    }
}
