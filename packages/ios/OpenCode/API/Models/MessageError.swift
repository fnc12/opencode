import Foundation

struct MessageError: Decodable {
    let name: String
    let message: String?

    private enum CodingKeys: String, CodingKey {
        case name, message, data
    }

    private struct ErrorData: Decodable {
        let message: String?
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        if let data = try? container.decode(ErrorData.self, forKey: .data) {
            message = data.message
        } else {
            message = try? container.decode(String.self, forKey: .message)
        }
    }

    var displayText: String {
        if let message, !message.isEmpty { return message }
        return name
    }
}
