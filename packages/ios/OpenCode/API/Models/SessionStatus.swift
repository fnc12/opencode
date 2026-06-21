import Foundation

struct SessionStatus: Decodable {
    let type: String
    let attempt: Int?
    let message: String?
    let next: Double?

    var isBusy: Bool { type == "busy" }
    var isIdle: Bool { type == "idle" }
}
