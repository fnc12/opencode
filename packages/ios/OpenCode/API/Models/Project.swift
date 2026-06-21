import Foundation

struct Project: Decodable, Identifiable {
    let id: String
    let worktree: String
    let vcs: String?
    let name: String?
    let time: ProjectTime
    let sandboxes: [String]
}

struct ProjectTime: Decodable {
    let created: Double
    let updated: Double
    let initialized: Double?
}
