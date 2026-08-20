import Foundation

/// A pending permission request from the agent (the `permission[.v2].asked`
/// event and the `GET /permission` list). The agent wants to perform an action
/// (run a command, edit a file, fetch a URL…) and is blocked until the user
/// replies `once`, `always`, or `reject`.
///
/// Speaks BOTH the v2 shape (`action` + `resources`) and the older v1 shape
/// (`permission` + `patterns`), so the app works against any server on the
/// migration path — an old server that only emits v1 no longer leaves the
/// prompt invisible (which silently hung the session).
struct PermissionRequest: Decodable, Identifiable, Equatable {
    let id: String
    let sessionID: String
    let action: String
    let resources: [String]

    private enum CodingKeys: String, CodingKey {
        case id, sessionID
        case action, resources          // v2
        case permission, patterns       // v1
    }

    init(id: String, sessionID: String, action: String, resources: [String]) {
        self.id = id
        self.sessionID = sessionID
        self.action = action
        self.resources = resources
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        sessionID = (try? c.decode(String.self, forKey: .sessionID)) ?? ""
        // v2 `action` ?? v1 `permission`; v2 `resources` ?? v1 `patterns`.
        action = (try? c.decode(String.self, forKey: .action))
            ?? (try? c.decode(String.self, forKey: .permission)) ?? ""
        resources = (try? c.decode([String].self, forKey: .resources))
            ?? (try? c.decode([String].self, forKey: .patterns)) ?? []
    }

    /// A human-readable one-liner for the dock, e.g. "Run command: npm test".
    var summary: String {
        let target = resources.joined(separator: ", ")
        switch action {
        case "bash": return target.isEmpty ? "Run a shell command" : "Run command: \(target)"
        case "edit", "write": return target.isEmpty ? "Modify a file" : "Modify file: \(target)"
        case "webfetch": return target.isEmpty ? "Fetch a URL" : "Fetch: \(target)"
        case "websearch": return "Search the web\(target.isEmpty ? "" : ": \(target)")"
        case "external_directory": return target.isEmpty ? "Access a directory outside the project" : "Access outside project: \(target)"
        default:
            let verb = action.prefix(1).uppercased() + action.dropFirst()
            return target.isEmpty ? verb : "\(verb): \(target)"
        }
    }
}
