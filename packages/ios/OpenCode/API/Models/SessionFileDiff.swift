import Foundation

/// One file's change in a session, from `GET /session/:id/diff`.
/// `patch` is a unified diff; `status` is added / deleted / modified.
struct SessionFileDiff: Decodable, Identifiable {
    let file: String?
    let patch: String?
    let additions: Int
    let deletions: Int
    let status: String?

    var id: String { file ?? UUID().uuidString }
}
