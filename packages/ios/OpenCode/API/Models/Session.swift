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
