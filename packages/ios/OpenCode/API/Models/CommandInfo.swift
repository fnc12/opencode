import Foundation

/// A slash command available in the session (`GET /command`) — built-in (e.g.
/// `init`, `review`) or project-defined. Running one posts it as a prompt.
struct CommandInfo: Decodable, Identifiable {
    let name: String
    let description: String?

    var id: String { name }
}
