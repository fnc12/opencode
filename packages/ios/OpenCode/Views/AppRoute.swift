import Foundation

/// Navigation routes inside the connected stack (project list → sessions →
/// session). Using a typed path lets "open folder" / "new session" push a
/// freshly-created session without conflicting navigationDestinations.
enum AppRoute: Hashable {
    case sessions(Project)
    case session(Session)
}

extension Project: Hashable {
    static func == (lhs: Project, rhs: Project) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
