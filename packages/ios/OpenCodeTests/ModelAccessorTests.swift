import XCTest
@testable import OpenCode

/// Covers the small computed accessors / display strings on the model layer that
/// the decode tests don't reach: PermissionRequest.summary's remaining action
/// branches, Session date accessors + Hashable, and MessageError.displayText.
final class ModelAccessorTests: XCTestCase {
    private func perm(action: String, resources: [String]) -> PermissionRequest {
        PermissionRequest(id: "p", sessionID: "s", action: action, resources: resources)
    }

    func testPermissionSummaryPerAction() {
        XCTAssertEqual(perm(action: "bash", resources: []).summary, "Run a shell command")
        XCTAssertEqual(perm(action: "edit", resources: ["a.swift"]).summary, "Modify file: a.swift")
        XCTAssertEqual(perm(action: "write", resources: []).summary, "Modify a file")
        XCTAssertEqual(perm(action: "webfetch", resources: []).summary, "Fetch a URL")
        XCTAssertEqual(perm(action: "webfetch", resources: ["https://x"]).summary, "Fetch: https://x")
        XCTAssertEqual(perm(action: "websearch", resources: []).summary, "Search the web")
        XCTAssertEqual(perm(action: "websearch", resources: ["swift"]).summary, "Search the web: swift")
        XCTAssertEqual(perm(action: "external_directory", resources: []).summary,
                       "Access a directory outside the project")
    }

    func testPermissionSummaryDefaultVerbCapitalised() {
        // Unknown action → capitalised verb, with/without a target.
        XCTAssertEqual(perm(action: "deploy", resources: []).summary, "Deploy")
        XCTAssertEqual(perm(action: "deploy", resources: ["prod"]).summary, "Deploy: prod")
    }

    // --- Session -------------------------------------------------------------

    func testSessionDateAccessorsAndHashable() {
        let s: Session = try! JSONDecoder().decode(
            Session.self,
            from: Data(#"{"id":"ses_1","projectID":"p","directory":"/w","title":"T","version":"1","time":{"created":1000,"updated":2000}}"#.utf8))
        // created/updated are epoch millis → divided by 1000 for the Date.
        XCTAssertEqual(s.time.createdDate, Date(timeIntervalSince1970: 1))
        XCTAssertEqual(s.time.updatedDate, Date(timeIntervalSince1970: 2))
        // Hashable is by id: a second session with the same id collapses in a Set.
        let same: Session = try! JSONDecoder().decode(
            Session.self,
            from: Data(#"{"id":"ses_1","projectID":"other","directory":"/z","title":"Z","version":"9","time":{"created":5,"updated":6}}"#.utf8))
        XCTAssertEqual(s, same)
        XCTAssertEqual(Set([s, same]).count, 1)
    }

    // --- MessageError --------------------------------------------------------

    func testMessageErrorDisplayTextFromDataMessage() {
        let e: MessageError = try! JSONDecoder().decode(
            MessageError.self,
            from: Data(#"{"name":"ProviderError","data":{"message":"rate limited"}}"#.utf8))
        XCTAssertEqual(e.displayText, "rate limited")
    }

    func testMessageErrorDisplayTextFallsBackToName() {
        // No message anywhere → the error name is shown.
        let e: MessageError = try! JSONDecoder().decode(
            MessageError.self, from: Data(#"{"name":"UnknownError"}"#.utf8))
        XCTAssertEqual(e.displayText, "UnknownError")
    }
}
