import Foundation

enum PartContent {
    case text(String)
    /// The model's private thinking, rendered dimmed/italic under a "THINKING" label.
    case reasoning(String)
    case tool(ToolContent)
    case stepStart(StepStartContent)
    case stepFinish(StepFinishContent)
    /// A committed change snapshot: the set of files touched in one assistant turn.
    case patch(PatchContent)
    /// A referenced file (IDE context attachment), rendered as a compact chip.
    case file(FileRefContent)
    /// A marker where earlier conversation was summarized/compacted.
    case compaction(auto: Bool)

    init?(from decoder: Decoder, type: String) throws {
        switch type {
        case "text":
            let payload = try TextPartPayload(from: decoder)
            self = .text(payload.text)
        case "reasoning":
            let payload = try TextPartPayload(from: decoder)
            self = .reasoning(payload.text)
        case "tool":
            self = .tool(try ToolContent(from: decoder))
        case "step-start":
            self = .stepStart(try StepStartContent(from: decoder))
        case "step-finish":
            self = .stepFinish(try StepFinishContent(from: decoder))
        case "patch":
            self = .patch(try PatchContent(from: decoder))
        case "file":
            self = .file(try FileRefContent(from: decoder))
        case "compaction":
            let payload = try CompactionPayload(from: decoder)
            self = .compaction(auto: payload.auto ?? false)
        default:
            return nil
        }
    }
}

private struct TextPartPayload: Decodable {
    let text: String
}

private struct CompactionPayload: Decodable {
    let auto: Bool?
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
    let metadata: ToolMetadata?
}

/// Per-tool result metadata. Fields are tool-specific and all optional; the
/// renderer reads whichever apply (edit → filediff, grep → matches, bash → exit).
struct ToolMetadata: Decodable {
    let filediff: FileDiff?
    let matches: Int?
    let exit: Int?
    let todos: [MetaTodo]?
    /// Unified diff produced by an `edit`/`write`, rendered as a colored diff.
    let diff: String?
    /// A `read`'s clean content preview (server-stripped of the XML envelope).
    let preview: String?
}

/// One todo entry from a `todowrite` tool's metadata (status + content, so we can
/// both show the completed/total ratio and render the checklist).
struct MetaTodo: Decodable {
    let status: String?
    let content: String?
}

/// Line-change counts for an `edit`/`write` tool, used for the "+N −M" badge.
struct FileDiff: Decodable {
    let additions: Int?
    let deletions: Int?
}

/// A `patch` part: the files committed in one assistant turn.
struct PatchContent: Decodable {
    let hash: String?
    let files: [String]
}

/// A `file` part: a referenced file with an optional line range encoded in the
/// `url` query (`?start=266&end=266`).
struct FileRefContent: Decodable {
    let filename: String?
    let url: String?
    let mime: String?
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
