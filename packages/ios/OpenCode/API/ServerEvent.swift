import Foundation

/// A decoded record from the OpenCode server SSE stream (`GET /event`).
///
/// Every record on the wire is `{ "id": …, "type": …, "properties": { … } }`.
/// We only model the events a live session view needs; anything else decodes
/// to `.other` so an unknown event never aborts the stream.
enum ServerEvent {
    /// A message's metadata was created or changed (`message.updated`).
    case messageUpdated(sessionID: String, info: MessageInfo)
    /// A full snapshot of a single part (`message.part.updated`).
    case partUpdated(sessionID: String, part: MessagePart)
    /// An incremental append to one field of a part (`message.part.delta`).
    case partDelta(PartDelta)
    /// A part was removed (`message.part.removed`).
    case partRemoved(sessionID: String, messageID: String, partID: String)
    /// A message was removed (`message.removed`).
    case messageRemoved(sessionID: String, messageID: String)
    /// Session metadata changed (`session.updated`) — carries the revert boundary.
    case sessionUpdated(sessionID: String, revertMessageID: String?)
    /// The agent is asking permission to act (`permission.v2.asked`).
    case permissionAsked(PermissionRequest)
    /// A permission request was answered/cleared (`permission.v2.replied`).
    case permissionReplied(sessionID: String, requestID: String)
    /// The agent is asking the user a question (`question.v2.asked`).
    case questionAsked(QuestionRequest)
    /// A question was answered or rejected (`question.v2.replied` / `.rejected`).
    case questionResolved(sessionID: String, requestID: String)
    /// The session's todo list changed (`todo.updated`).
    case todoUpdated(sessionID: String, todos: [TodoItem])
    /// Any event type we don't model.
    case other(type: String)
}

/// The payload of a `message.part.delta` event: append `delta` to `field` of
/// the part identified by `partID` (live-only; reconciled by a later snapshot).
struct PartDelta {
    let sessionID: String
    let messageID: String
    let partID: String
    let field: String
    let delta: String
}

extension ServerEvent: Decodable {
    private enum CodingKeys: String, CodingKey { case type, properties, payload }
    private enum Prop: String, CodingKey {
        case sessionID, info, part, messageID, partID, field, delta, requestID, todos
    }

    init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: CodingKeys.self)
        // The global stream (`/global/event`) wraps each event in `payload`
        // alongside `directory`/`project`; the instance stream (`/event`) is
        // flat. Descend into `payload` when present so both forms decode.
        if top.contains(.payload) {
            try self.init(event: top.nestedContainer(keyedBy: CodingKeys.self, forKey: .payload))
        } else {
            try self.init(event: top)
        }
    }

    private init(event c: KeyedDecodingContainer<CodingKeys>) throws {
        let type = try c.decode(String.self, forKey: .type)

        switch type {
        case "message.updated":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .messageUpdated(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                info: try p.decode(MessageInfo.self, forKey: .info))
        case "message.part.updated":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .partUpdated(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                part: try p.decode(MessagePart.self, forKey: .part))
        case "message.part.delta":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .partDelta(PartDelta(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                messageID: try p.decode(String.self, forKey: .messageID),
                partID: try p.decode(String.self, forKey: .partID),
                field: try p.decode(String.self, forKey: .field),
                delta: try p.decode(String.self, forKey: .delta)))
        case "message.part.removed":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .partRemoved(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                messageID: try p.decode(String.self, forKey: .messageID),
                partID: try p.decode(String.self, forKey: .partID))
        case "message.removed":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .messageRemoved(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                messageID: try p.decode(String.self, forKey: .messageID))
        case "session.updated":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            let info = try? p.decode(Session.self, forKey: .info)
            let sid = info?.id ?? (try? p.decode(String.self, forKey: .sessionID))
            self = .sessionUpdated(sessionID: sid ?? "", revertMessageID: info?.revert?.messageID)
        case "permission.v2.asked":
            // The event properties ARE the permission request (id, sessionID, action, resources…).
            self = .permissionAsked(try c.decode(PermissionRequest.self, forKey: .properties))
        case "permission.v2.replied":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .permissionReplied(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                requestID: try p.decode(String.self, forKey: .requestID))
        case "question.v2.asked":
            self = .questionAsked(try c.decode(QuestionRequest.self, forKey: .properties))
        case "question.v2.replied", "question.v2.rejected":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .questionResolved(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                requestID: try p.decode(String.self, forKey: .requestID))
        case "todo.updated":
            let p = try c.nestedContainer(keyedBy: Prop.self, forKey: .properties)
            self = .todoUpdated(
                sessionID: try p.decode(String.self, forKey: .sessionID),
                todos: (try? p.decode([TodoItem].self, forKey: .todos)) ?? [])
        default:
            self = .other(type: type)
        }
    }
}
