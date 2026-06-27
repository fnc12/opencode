import Foundation
import Observation

/// Holds the live state of one session's conversation and folds SSE events into
/// it. Seeded with the REST snapshot, then `apply(_:sessionID:)` upserts/removes
/// messages and parts and appends streaming text deltas — mirroring how the web
/// client consumes `/event`.
///
/// Every applied event reassigns `messages` wholesale (via `commit`). SwiftUI's
/// `@Observable` reliably re-renders on a top-level array assignment but not
/// always on an in-place nested mutation like `messages[i].parts[j] = …`, which
/// would otherwise leave streamed text invisible until the view is reopened.
@MainActor
@Observable
final class SessionStore {
    enum StreamStatus { case idle, connecting, live, reconnecting }

    private(set) var messages: [MessageWithParts] = []
    private(set) var status: StreamStatus = .idle
    /// Pending permission requests for this session (the agent is blocked on them).
    private(set) var pendingPermissions: [PermissionRequest] = []
    /// Bumped on every applied change so the view can react (e.g. auto-scroll)
    /// even when text grows inside an existing message.
    private(set) var revision = 0

    func setInitial(_ messages: [MessageWithParts]) {
        self.messages = messages.sorted { Self.createdAt($0) < Self.createdAt($1) }
        revision += 1
    }

    func setInitialPermissions(_ permissions: [PermissionRequest]) {
        pendingPermissions = permissions
    }

    /// Drop a permission locally (optimistically, after the user answers it).
    func dismissPermission(id: String) {
        pendingPermissions = pendingPermissions.filter { $0.id != id }
    }

    func setStatus(_ status: StreamStatus) {
        self.status = status
    }

    func apply(_ event: ServerEvent, sessionID: String) {
        switch event {
        case .messageUpdated(let sid, let info) where sid == sessionID:
            commit { upsertMessage(&$0, info: info) }
        case .partUpdated(let sid, let part) where sid == sessionID:
            commit { upsertPart(&$0, part: part) }
        case .partDelta(let delta) where delta.sessionID == sessionID:
            commit { appendDelta(&$0, delta: delta) }
        case .partRemoved(let sid, let messageID, let partID) where sid == sessionID:
            commit { msgs in
                if let i = msgs.firstIndex(where: { $0.id == messageID }) {
                    msgs[i].parts.removeAll { $0.id == partID }
                }
            }
        case .messageRemoved(let sid, let messageID) where sid == sessionID:
            commit { $0.removeAll { $0.id == messageID } }
        case .permissionAsked(let request) where request.sessionID == sessionID:
            if !pendingPermissions.contains(where: { $0.id == request.id }) {
                pendingPermissions = pendingPermissions + [request]
            }
        case .permissionReplied(let sid, let requestID) where sid == sessionID:
            dismissPermission(id: requestID)
        default:
            return // not ours / unmodeled: no revision bump
        }
        revision += 1
    }

    /// Applies a mutation to a working copy then assigns it back as a fresh
    /// array so SwiftUI re-renders (in-place nested mutation isn't observed
    /// reliably). Ordering is handled at insertion time, not here, so streaming
    /// deltas stay O(1) in the message count rather than re-sorting every chunk.
    private func commit(_ body: (inout [MessageWithParts]) -> Void) {
        var working = messages
        body(&working)
        messages = working
    }

    private func upsertMessage(_ msgs: inout [MessageWithParts], info: MessageInfo) {
        if let i = msgs.firstIndex(where: { $0.id == info.id }) {
            msgs[i].info = info // metadata only; created time (and thus order) is stable
        } else {
            msgs.append(MessageWithParts(info: info, parts: []))
            // Order can only change when a message is added — sort here, not per delta.
            msgs.sort { Self.createdAt($0) < Self.createdAt($1) }
        }
    }

    private func upsertPart(_ msgs: inout [MessageWithParts], part: MessagePart) {
        guard let i = msgs.firstIndex(where: { $0.id == part.messageID }) else { return }
        if let j = msgs[i].parts.firstIndex(where: { $0.id == part.id }) {
            msgs[i].parts[j] = part
        } else {
            msgs[i].parts.append(part)
        }
    }

    private func appendDelta(_ msgs: inout [MessageWithParts], delta: PartDelta) {
        guard delta.field == "text" else { return } // only text streams visibly for now
        guard let i = msgs.firstIndex(where: { $0.id == delta.messageID }) else { return }
        if let j = msgs[i].parts.firstIndex(where: { $0.id == delta.partID }) {
            msgs[i].parts[j] = msgs[i].parts[j].appendingText(delta.delta)
        } else {
            // Delta arrived before the first snapshot: synthesize a text part.
            msgs[i].parts.append(MessagePart(
                id: delta.partID, sessionID: delta.sessionID, messageID: delta.messageID,
                type: "text", content: .text(delta.delta)))
        }
    }

    private static func createdAt(_ message: MessageWithParts) -> Double {
        switch message.info {
        case .user(let m): m.time.created
        case .assistant(let m): m.time.created
        }
    }
}
