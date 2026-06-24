import Foundation

struct MessageWithParts: Decodable, Identifiable {
    var info: MessageInfo
    var parts: [MessagePart]

    var id: String { info.id }

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
