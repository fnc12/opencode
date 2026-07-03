import Foundation

struct Session: Decodable, Identifiable {
    let id: String
    let slug: String?
    let projectID: String
    let directory: String
    let parentID: String?
    let title: String
    let version: String
    let time: SessionTime
    let summary: SessionSummary?
    let share: SessionShare?
    /// Set when the session has been reverted to a point — messages from
    /// `messageID` onward are undone.
    let revert: SessionRevert?
}

struct SessionRevert: Decodable {
    let messageID: String
}

struct SessionTime: Decodable {
    let created: Double
    let updated: Double
    let compacting: Double?
    let archived: Double?

    var createdDate: Date { Date(timeIntervalSince1970: created / 1000) }
    var updatedDate: Date { Date(timeIntervalSince1970: updated / 1000) }
}

struct SessionSummary: Decodable {
    let additions: Int
    let deletions: Int
    let files: Int
}

struct SessionShare: Decodable {
    let url: String
}

extension Session: Hashable {
    static func == (lhs: Session, rhs: Session) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
