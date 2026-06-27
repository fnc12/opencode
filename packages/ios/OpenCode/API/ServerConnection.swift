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
        if let password = config.password, !password.isEmpty {
            let cred = Data("admin:\(password)".utf8).base64EncodedString()
            request.setValue("Basic \(cred)", forHTTPHeaderField: "Authorization")
        }
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

    /// Sends a text prompt to a session. The assistant's reply streams back over
    /// the event stream, so the caller doesn't need the response body. A model is
    /// required — the server has no default.
    func sendPrompt(directory: String, sessionID: String, text: String,
                    providerID: String, modelID: String) async throws {
        try await post("/session/\(sessionID)/message", query: ["directory": directory], body: [
            "parts": [["type": "text", "text": text]],
            "model": ["providerID": providerID, "modelID": modelID],
        ])
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
        if let password = config.password, !password.isEmpty {
            let cred = Data("admin:\(password)".utf8).base64EncodedString()
            request.setValue("Basic \(cred)", forHTTPHeaderField: "Authorization")
        }
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
            let cred = Data("admin:\(password)".utf8).base64EncodedString()
            authHeader = "Basic \(cred)"
        }
        return EventStream(url: url, authHeader: authHeader)
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
        if let password = config.password, !password.isEmpty {
            let cred = Data("admin:\(password)".utf8).base64EncodedString()
            request.setValue("Basic \(cred)", forHTTPHeaderField: "Authorization")
        }
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
