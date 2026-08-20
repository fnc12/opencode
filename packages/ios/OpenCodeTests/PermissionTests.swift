import XCTest
@testable import OpenCode

/// Decoding permission events and folding them into the session's pending list.
final class PermissionDecodeTests: XCTestCase {
    private func decode(_ json: String) throws -> ServerEvent {
        try JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    func testDecodesPermissionAsked() throws {
        let json = """
        {"directory":"/x","project":"p","payload":{"id":"evt_1","type":"permission.v2.asked",
        "properties":{"id":"perm_1","sessionID":"ses_1","action":"bash","resources":["echo hi"],"metadata":{}}}}
        """
        guard case .permissionAsked(let req) = try decode(json) else { return XCTFail("expected .permissionAsked") }
        XCTAssertEqual(req.id, "perm_1")
        XCTAssertEqual(req.sessionID, "ses_1")
        XCTAssertEqual(req.action, "bash")
        XCTAssertEqual(req.resources, ["echo hi"])
        XCTAssertEqual(req.summary, "Run command: echo hi")
    }

    func testDecodesPermissionReplied() throws {
        let json = #"{"payload":{"type":"permission.v2.replied","properties":{"sessionID":"ses_1","requestID":"perm_1","reply":"once"}}}"#
        guard case .permissionReplied(let sid, let requestID) = try decode(json) else { return XCTFail("expected .permissionReplied") }
        XCTAssertEqual(sid, "ses_1")
        XCTAssertEqual(requestID, "perm_1")
    }

    /// Compat: an older server emits the v1 event name (`permission.asked`) with
    /// v1 fields (`permission` + `patterns`). We must still surface it — the
    /// regression that silently hung external-directory reads.
    func testDecodesV1PermissionAsked() throws {
        let json = """
        {"payload":{"type":"permission.asked","properties":{"id":"perm_2","sessionID":"ses_1",
        "permission":"external_directory","patterns":["/mnt/lib/*"],"metadata":{}}}}
        """
        guard case .permissionAsked(let req) = try decode(json) else { return XCTFail("expected .permissionAsked") }
        XCTAssertEqual(req.id, "perm_2")
        XCTAssertEqual(req.action, "external_directory")     // mapped from `permission`
        XCTAssertEqual(req.resources, ["/mnt/lib/*"])        // mapped from `patterns`
        XCTAssertEqual(req.summary, "Access outside project: /mnt/lib/*")
    }

    func testDecodesV1PermissionReplied() throws {
        let json = #"{"payload":{"type":"permission.replied","properties":{"sessionID":"ses_1","requestID":"perm_2","reply":"reject"}}}"#
        guard case .permissionReplied(_, let requestID) = try decode(json) else { return XCTFail("expected .permissionReplied") }
        XCTAssertEqual(requestID, "perm_2")
    }

    /// A permission whose properties carry only `id` — no sessionID, and neither
    /// the v2 (`action`/`resources`) nor v1 (`permission`/`patterns`) fields.
    /// Exercises the terminal `?? ""` / `?? []` defaults in PermissionRequest.
    func testDecodesSparsePermissionUsesDefaults() throws {
        let json = #"{"payload":{"type":"permission.v2.asked","properties":{"id":"perm_9"}}}"#
        guard case .permissionAsked(let req) = try decode(json) else { return XCTFail("expected .permissionAsked") }
        XCTAssertEqual(req.id, "perm_9")
        XCTAssertEqual(req.sessionID, "")
        XCTAssertEqual(req.action, "")
        XCTAssertEqual(req.resources, [])
    }
}

@MainActor
final class PermissionStoreTests: XCTestCase {
    private let sessionID = "ses_1"

    private func event(_ json: String) -> ServerEvent {
        try! JSONDecoder().decode(ServerEvent.self, from: Data(json.utf8))
    }

    private func asked(id: String, session: String) -> ServerEvent {
        event("""
        {"type":"permission.v2.asked","properties":{"id":"\(id)","sessionID":"\(session)","action":"bash","resources":["x"]}}
        """)
    }

    private func replied(id: String, session: String) -> ServerEvent {
        event("""
        {"type":"permission.v2.replied","properties":{"sessionID":"\(session)","requestID":"\(id)","reply":"once"}}
        """)
    }

    func testAskedAddsThenRepliedRemoves() {
        let store = SessionStore()
        store.apply(asked(id: "perm_1", session: sessionID), sessionID: sessionID)
        XCTAssertEqual(store.pendingPermissions.map(\.id), ["perm_1"])
        store.apply(replied(id: "perm_1", session: sessionID), sessionID: sessionID)
        XCTAssertTrue(store.pendingPermissions.isEmpty)
    }

    func testDeduplicatesSameRequest() {
        let store = SessionStore()
        store.apply(asked(id: "perm_1", session: sessionID), sessionID: sessionID)
        store.apply(asked(id: "perm_1", session: sessionID), sessionID: sessionID)
        XCTAssertEqual(store.pendingPermissions.count, 1)
    }

    func testIgnoresOtherSessions() {
        let store = SessionStore()
        store.apply(asked(id: "perm_1", session: "OTHER"), sessionID: sessionID)
        XCTAssertTrue(store.pendingPermissions.isEmpty)
    }

    func testDismissRemovesOptimistically() {
        let store = SessionStore()
        store.apply(asked(id: "perm_1", session: sessionID), sessionID: sessionID)
        store.dismissPermission(id: "perm_1")
        XCTAssertTrue(store.pendingPermissions.isEmpty)
    }
}
