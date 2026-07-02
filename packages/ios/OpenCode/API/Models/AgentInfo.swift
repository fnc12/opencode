import Foundation

/// A selectable AI agent, from `GET /agent`. `mode` is subagent / primary / all;
/// only primary/all (non-hidden) agents are user-selectable in the composer.
struct AgentInfo: Decodable, Identifiable {
    let name: String
    let description: String?
    let mode: String
    let hidden: Bool?

    var id: String { name }
    var selectable: Bool { mode != "subagent" && !(hidden ?? false) }
}
