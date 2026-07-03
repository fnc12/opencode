import Foundation

struct MessageWithParts: Decodable, Identifiable {
    var info: MessageInfo
    var parts: [MessagePart]

    var id: String { info.id }

    /// Whether the message renders anything at all. A message whose only parts are
    /// synthetic/ignored (e.g. the "The following tool was executed by the user"
    /// filler `/shell` posts) or empty produces an empty bubble — skip it.
    var hasRenderableContent: Bool {
        parts.contains { part in
            guard part.isVisible else { return false }
            switch part.content {
            case .text(let text): return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .reasoning(let text): return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .tool, .patch, .file, .compaction: return true
            default: return false
            }
        }
    }

    init(info: MessageInfo, parts: [MessagePart]) {
        self.info = info
        self.parts = parts
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        info = try container.decode(MessageInfo.self, forKey: .info)
        parts = try container.decode([MessagePart].self, forKey: .parts)
    }

    private enum CodingKeys: String, CodingKey { case info, parts }
}
