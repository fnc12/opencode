import SwiftUI

/// One command + its output in the pocket terminal.
struct ShellEntry: Identifiable {
    let id = UUID()
    let command: String
    let output: String
}

/// Keeps each session's shell history alive across opening/closing the terminal
/// sheet (and navigating away and back), so tapping Done no longer throws away
/// what you ran. In-memory for the app's lifetime, keyed by session id.
///
/// NOTE: this is the one-shot `/session/:id/shell` model (each command runs
/// independently — no shared cwd/env). Real persistent, interactive, multi-tab
/// terminals are a follow-up on the server's PTY API.
@Observable
@MainActor
final class ShellStore {
    static let shared = ShellStore()
    private init() {}

    private var histories: [String: [ShellEntry]] = [:]

    func history(_ sessionID: String) -> [ShellEntry] { histories[sessionID] ?? [] }
    func append(_ entry: ShellEntry, to sessionID: String) {
        histories[sessionID, default: []].append(entry)
    }
    func clear(_ sessionID: String) { histories[sessionID] = [] }
}
