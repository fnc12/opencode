import Foundation
import Observation

@MainActor
@Observable
final class ServerConnection {
    /// The active connection configuration (relay or direct).
    var config = ConnectionConfig()
    var connected = false
    var version = ""
    var error: String?
    var loading = false

    init() {
        if let saved = Keychain.loadConnection() {
            config = saved
        }
    }

    /// Attempts to connect using the current config and, on success, persists it.
    func connect() async {
        loading = true
        error = nil
        defer { loading = false }

        do {
            let health: HealthResponse = try await get("/global/health")
            version = health.version
            connected = true
            Keychain.saveConnection(config)
        } catch {
            self.error = error.localizedDescription
            connected = false
        }
    }

    /// Applies a pairing payload (from QR, deep link, or manual paste).
    @discardableResult
    func applyPairing(_ raw: String) -> Bool {
        guard let parsed = ConnectionConfig.fromPairing(raw) else { return false }
        config = parsed
        return true
    }

    func disconnect() {
        connected = false
        version = ""
    }

    /// Forgets the saved connection and resets state.
    func forget() {
        Keychain.clearConnection()
        config = ConnectionConfig()
        disconnect()
    }

    func projects() async throws -> [Project] {
        try await get("/project")
    }

    func sessions(directory: String) async throws -> [Session] {
        try await get("/session", query: ["directory": directory])
    }

    func messages(directory: String, sessionID: String) async throws -> [MessageWithParts] {
        try await get("/session/\(sessionID)/message", query: ["directory": directory])
    }

    /// The aggregate file changes for a session (`GET /session/:id/diff`) — one
    /// unified diff per touched file.
    func sessionDiff(directory: String, sessionID: String) async throws -> [SessionFileDiff] {
        try await get("/session/\(sessionID)/diff", query: ["directory": directory])
    }

    /// The session's current todo list (`GET /session/:id/todo`).
    func sessionTodos(directory: String, sessionID: String) async throws -> [TodoItem] {
        try await get("/session/\(sessionID)/todo", query: ["directory": directory])
    }

    /// Slash commands available for this server (`GET /command`).
    func commands(directory: String) async throws -> [CommandInfo] {
        try await get("/command", query: ["directory": directory])
    }

    /// Auth methods available per provider (`GET /provider/auth`) — a map of
    /// providerID → the ways you can authenticate it (api key / oauth).
    func providerAuthMethods() async throws -> [String: [ProviderAuthMethod]] {
        try await get("/provider/auth")
    }

    /// Set an API key for a provider (`PUT /auth/:id`).
    func setProviderKey(providerID: String, key: String) async throws {
        let _: Bool = try await sendForResult("PUT", "/auth/\(providerID)", body: ["type": "api", "key": key])
    }

    /// Remove a provider's stored credentials (`DELETE /auth/:id`).
    func removeProviderAuth(providerID: String) async throws {
        let _: Bool = try await sendForResult("DELETE", "/auth/\(providerID)")
    }

    /// Run a slash command in the session (`POST /session/:id/command`) — it is
    /// expanded to a prompt and streams back like any other turn.
    func runCommand(directory: String, sessionID: String, command: String, arguments: String = "") async throws {
        try await post("/session/\(sessionID)/command", query: ["directory": directory],
                       body: ["command": command, "arguments": arguments])
    }

    /// Run a shell command in the session context (`POST /session/:id/shell`) and
    /// return its combined output — the terminal in your pocket.
    func runShell(directory: String, sessionID: String, command: String, agent: String = "build") async throws -> String {
        let message: MessageWithParts = try await sendForResult(
            "POST", "/session/\(sessionID)/shell",
            query: ["directory": directory], body: ["agent": agent, "command": command])
        for part in message.parts {
            if case .tool(let tool)? = part.content, let output = tool.state.output, !output.isEmpty {
                return output
            }
        }
        let text = message.parts.compactMap { part -> String? in
            if case .text(let value)? = part.content, part.isVisible, !value.isEmpty { return value }
            return nil
        }.joined(separator: "\n")
        return text.isEmpty ? "(no output)" : text
    }

    /// Lists the entries (folders + files) of a directory on the server, for the
    /// folder browser. Works for any path the server can read.
    func listDirectory(path: String) async throws -> [FileEntry] {
        try await get("/file", query: ["directory": path, "path": "."])
    }

    /// Reads a file's text content (`GET /file/content`) — used to attach a repo
    /// file as prompt context.
    func readFile(directory: String, path: String) async throws -> String {
        struct FileContent: Decodable { let content: String }
        let result: FileContent = try await get("/file/content", query: ["directory": directory, "path": path])
        return result.content
    }

    /// Creates a new session in a directory and returns it. Works for any folder
    /// the server can see — including on a fresh server with no projects yet.
    func createSession(directory: String, title: String? = nil) async throws -> Session {
        guard var components = URLComponents(string: config.baseURL + "/session") else {
            throw ClientError.invalidURL
        }
        components.queryItems = [URLQueryItem(name: "directory", value: directory)]
        guard let url = components.url else { throw ClientError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyAuth(to: &request)
        var body: [String: Any] = [:]
        if let title, !title.isEmpty { body["title"] = title }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let text = String(data: data, encoding: .utf8) ?? "(non-utf8)"
            print("❌ createSession \(code): \(text.prefix(400))")
            throw ClientError.http(code)
        }
        return try JSONDecoder().decode(Session.self, from: data)
    }

    /// Available providers and their models (for the composer's model picker).
    func providers() async throws -> [ProviderInfo] {
        let response: ProvidersResponse = try await get("/config/providers")
        return response.providers
    }

    /// The available agents (`GET /agent`) — build / plan / custom. The composer
    /// offers the non-hidden primary ones.
    func agents() async throws -> [AgentInfo] {
        try await get("/agent")
    }

    /// Pending permission requests across all sessions (seed on session open).
    func permissions(directory: String) async throws -> [PermissionRequest] {
        try await get("/permission", query: ["directory": directory])
    }

    /// Answer a permission request: `once`, `always`, or `reject`.
    func replyPermission(directory: String, requestID: String, reply: String) async throws {
        try await post("/permission/\(requestID)/reply", query: ["directory": directory], body: ["reply": reply])
    }

    /// Pending questions across all sessions (seed on session open).
    func questions(directory: String) async throws -> [QuestionRequest] {
        try await get("/question", query: ["directory": directory])
    }

    /// Answer a question — one array of selected labels per question.
    func replyQuestion(directory: String, requestID: String, answers: [[String]]) async throws {
        try await post("/question/\(requestID)/reply", query: ["directory": directory], body: ["answers": answers])
    }

    /// Reject a question (dismiss without answering).
    func rejectQuestion(directory: String, requestID: String) async throws {
        try await post("/question/\(requestID)/reject", query: ["directory": directory], body: [:])
    }

    /// Abort a session — stop any ongoing AI processing / command execution.
    func abort(directory: String, sessionID: String) async throws {
        try await post("/session/\(sessionID)/abort", query: ["directory": directory], body: [:])
    }

    /// Revert the session to just before `messageID` — undoes that message and
    /// everything after it, including the file changes. `POST /session/:id/revert`.
    func revertSession(directory: String, sessionID: String, messageID: String) async throws {
        try await post("/session/\(sessionID)/revert", query: ["directory": directory], body: ["messageID": messageID])
    }

    /// Restore all reverted messages. `POST /session/:id/unrevert`.
    func unrevertSession(directory: String, sessionID: String) async throws {
        try await post("/session/\(sessionID)/unrevert", query: ["directory": directory], body: [:])
    }

    /// Rename a session (sets its title). `PATCH /session/:id`.
    func renameSession(directory: String, sessionID: String, title: String) async throws {
        try await send("PATCH", "/session/\(sessionID)", query: ["directory": directory], body: ["title": title])
    }

    /// Delete a session. `DELETE /session/:id`.
    func deleteSession(directory: String, sessionID: String) async throws {
        try await send("DELETE", "/session/\(sessionID)", query: ["directory": directory], body: nil)
    }

    /// Create a public share link (`POST /session/:id/share`) — returns the
    /// updated session whose `share.url` is the link.
    func shareSession(directory: String, sessionID: String) async throws -> Session {
        try await sendForResult("POST", "/session/\(sessionID)/share", query: ["directory": directory])
    }

    /// Stop sharing (`DELETE /session/:id/share`).
    func unshareSession(directory: String, sessionID: String) async throws -> Session {
        try await sendForResult("DELETE", "/session/\(sessionID)/share", query: ["directory": directory])
    }

    /// Like `send` but decodes and returns the response body.
    private func sendForResult<T: Decodable>(_ method: String, _ path: String,
                                             query: [String: String] = [:], body: [String: Any]? = nil) async throws -> T {
        guard var components = URLComponents(string: config.baseURL + path) else { throw ClientError.invalidURL }
        if !query.isEmpty { components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } }
        guard let url = components.url else { throw ClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        applyAuth(to: &request)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw ClientError.http((response as? HTTPURLResponse)?.statusCode ?? 0)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    /// Generic request for verbs beyond GET/POST (PATCH/DELETE), body optional.
    private func send(_ method: String, _ path: String,
                      query: [String: String] = [:], body: [String: Any]? = nil) async throws {
        guard var components = URLComponents(string: config.baseURL + path) else { throw ClientError.invalidURL }
        if !query.isEmpty { components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } }
        guard let url = components.url else { throw ClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        applyAuth(to: &request)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            print("❌ \(method) \(code): \(url.absoluteString)\n\(String(data: data, encoding: .utf8)?.prefix(400) ?? "")")
            throw ClientError.http(code)
        }
    }

    /// Sends a text prompt to a session. The assistant's reply streams back over
    /// the event stream, so the caller doesn't need the response body. A model is
    /// required — the server has no default.
    func sendPrompt(directory: String, sessionID: String, text: String,
                    providerID: String, modelID: String, agent: String? = nil,
                    attachments: [[String: Any]] = []) async throws {
        // Note: the server holds this POST open until the whole turn finishes,
        // but the turn keeps generating (and streaming over SSE) even if the POST
        // stops being awaited. The composer sends this fire-and-forget, so the
        // POST's timeout never gates the UI.
        // `attachments` are already complete part dicts (image file parts, or
        // repo-file context parts with a `source`), prepended before the text.
        var parts: [[String: Any]] = attachments
        parts.append(["type": "text", "text": text])
        var body: [String: Any] = [
            "parts": parts,
            "model": ["providerID": providerID, "modelID": modelID],
        ]
        if let agent, !agent.isEmpty { body["agent"] = agent }
        try await post("/session/\(sessionID)/message", query: ["directory": directory], body: body)
    }

    /// In relay mode, the per-tunnel token the relay checks (`X-Tunnel-Token`)
    /// before proxying to the connector; nil in direct mode.
    private var tunnelToken: String? {
        config.mode == .relay && !config.token.isEmpty ? config.token : nil
    }

    /// Applies the auth headers every request shares: the OpenCode server
    /// password as Basic auth (forwarded through the connector) and, in relay
    /// mode, the X-Tunnel-Token the relay requires.
    private func applyAuth(to request: inout URLRequest) {
        if let password = config.password, !password.isEmpty {
            let cred = Data("opencode:\(password)".utf8).base64EncodedString()
            request.setValue("Basic \(cred)", forHTTPHeaderField: "Authorization")
        }
        if let tunnelToken {
            request.setValue(tunnelToken, forHTTPHeaderField: "X-Tunnel-Token")
        }
    }

    func post(_ path: String, query: [String: String] = [:], body: [String: Any]) async throws {
        guard var components = URLComponents(string: config.baseURL + path) else {
            throw ClientError.invalidURL
        }
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components.url else { throw ClientError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        applyAuth(to: &request)
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let text = String(data: data, encoding: .utf8) ?? "(non-utf8)"
            print("❌ POST \(code): \(url.absoluteString)\n\(text.prefix(500))")
            throw ClientError.http(code)
        }
    }

    /// Builds an SSE reader for the global event stream (`GET /global/event`).
    /// The instance stream (`/event`) only emits `server.connected`; all live
    /// session activity (message + part events) is published on the global bus,
    /// which is also what the relay's push subscriber listens to. In relay mode
    /// this resolves to `/t/{tunnelID}/global/event`. Events arrive for every
    /// session; callers filter by `sessionID`.
    func eventStream(directory: String) -> EventStream? {
        guard let url = URL(string: config.baseURL + "/global/event") else {
            return nil
        }

        var authHeader: String?
        if let password = config.password, !password.isEmpty {
            let cred = Data("opencode:\(password)".utf8).base64EncodedString()
            authHeader = "Basic \(cred)"
        }
        return EventStream(url: url, authHeader: authHeader, tunnelToken: tunnelToken)
    }

    func get<T: Decodable>(_ path: String, query: [String: String] = [:]) async throws -> T {
        guard var components = URLComponents(string: config.baseURL + path) else {
            throw ClientError.invalidURL
        }
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components.url else {
            throw ClientError.invalidURL
        }
        var request = URLRequest(url: url)
        // The OpenCode server's password (OPENCODE_SERVER_PASSWORD) is forwarded
        // as Basic auth; in relay mode the connector passes the header through.
        applyAuth(to: &request)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let body = String(data: data, encoding: .utf8) ?? "(non-utf8)"
            print("❌ HTTP \(code): \(request.url?.absoluteString ?? "")\n\(body)")
            throw ClientError.http(code)
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            let body = String(data: data, encoding: .utf8) ?? "(non-utf8)"
            print("❌ Decode \(T.self) failed: \(error)\n📦 Response: \(body.prefix(2000))")
            throw error
        }
    }
}
