import Foundation

enum PartContent {
    case text(String)
    case tool(ToolContent)
    case stepStart(StepStartContent)
    case stepFinish(StepFinishContent)

    init?(from decoder: Decoder, type: String) throws {
        switch type {
        case "text":
            let payload = try TextPartPayload(from: decoder)
            self = .text(payload.text)
        case "tool":
            self = .tool(try ToolContent(from: decoder))
        case "step-start":
            self = .stepStart(try StepStartContent(from: decoder))
        case "step-finish":
            self = .stepFinish(try StepFinishContent(from: decoder))
        default:
            return nil
        }
    }
}

private struct TextPartPayload: Decodable {
    let text: String
}

struct ToolContent: Decodable {
    let tool: String
    let callID: String
    let state: ToolState
}

struct ToolState: Decodable {
    let status: String
    let input: [String: AnyCodable]?
    let output: String?
    let error: String?
    let title: String?
    let time: ToolTime?
}

struct ToolTime: Decodable {
    let start: Double
    let end: Double?

    var duration: Double? {
        guard let end else { return nil }
        return end - start
    }
}

struct StepStartContent: Decodable {
    let title: String?
}

struct StepFinishContent: Decodable {
    let reason: String?
}

struct AnyCodable: Decodable {
    let value: Any

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let s = try? container.decode(String.self) { value = s }
        else if let i = try? container.decode(Int.self) { value = i }
        else if let d = try? container.decode(Double.self) { value = d }
        else if let b = try? container.decode(Bool.self) { value = b }
        else { value = "" }
    }
}
