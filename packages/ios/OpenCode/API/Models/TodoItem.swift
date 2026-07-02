import Foundation

/// One task in a session's todo list (`GET /session/:id/todo`, `todo.updated`).
/// `status` is pending / in_progress / completed / cancelled.
struct TodoItem: Decodable, Identifiable {
    let content: String
    let status: String
    let priority: String?

    var id: String { content }
    var done: Bool { status == "completed" || status == "cancelled" }
}
