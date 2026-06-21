import Foundation

struct MessageWithParts: Decodable, Identifiable {
    let info: MessageInfo
    let parts: [MessagePart]

    var id: String { info.id }
}
