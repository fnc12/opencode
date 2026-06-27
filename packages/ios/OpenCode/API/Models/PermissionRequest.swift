import Foundation

/// A pending permission request from the agent (`permission.v2.asked` event and
/// the `GET /permission` list). The agent wants to perform an action (run a
/// command, edit a file, fetch a URL…) and is blocked until the user replies
/// `once`, `always`, or `reject`.
struct PermissionRequest: Decodable, Identifiable, Equatable {
    let id: String
    let sessionID: String
    let action: String
    let resources: [String]

    private enum CodingKeys: String, CodingKey { case id, sessionID, action, resources }

    /// A human-readable one-liner for the dock, e.g. "Run command: npm test".
    var summary: String {
        let target = resources.joined(separator: ", ")
        switch action {
        case "bash": return target.isEmpty ? "Run a shell command" : "Run command: \(target)"
        case "edit", "write": return target.isEmpty ? "Modify a file" : "Modify file: \(target)"
        case "webfetch": return target.isEmpty ? "Fetch a URL" : "Fetch: \(target)"
        case "websearch": return "Search the web\(target.isEmpty ? "" : ": \(target)")"
        default:
            let verb = action.prefix(1).uppercased() + action.dropFirst()
            return target.isEmpty ? verb : "\(verb): \(target)"
        }
    }
}
