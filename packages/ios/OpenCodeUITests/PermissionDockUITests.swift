import XCTest

/// Drives the permission dock on the simulator: open a session with an injected
/// pending permission (launch arg `UITEST_PERMISSION`), assert the dock renders,
/// tap **Allow**, and assert it dismisses. Verifies the #27 UI + reply wiring
/// without needing the server to be configured to "ask".
///
/// Requires a reachable server at `127.0.0.1:4096` (SSH tunnel) for the
/// connect→session navigation; skipped otherwise.
@MainActor
final class PermissionDockUITests: XCTestCase {
    private let base = "http://127.0.0.1:4096"
    private let dir = "/mnt/data/sources/sqlite_orm"
    private let projectName = "sqlite_orm"

    func testApprovePermissionFromDock() async throws {
        let reachable = await serverReachable()
        try XCTSkipUnless(reachable, "live server not reachable at \(base); start the tunnel to run this UI test")

        let stamp = Int(Date().timeIntervalSince1970)
        let title = "PERMTEST \(stamp)"
        try await createSession(title: title)

        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET", "UITEST_PERMISSION"]
        app.launch()

        let direct = app.buttons["Direct"]
        XCTAssertTrue(direct.waitForExistence(timeout: 10))
        direct.tap()
        let urlField = app.textFields["connect.serverURL"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 5))
        urlField.tap(); urlField.typeText(base)
        app.buttons["connect.button"].tap()

        let project = app.staticTexts[projectName]
        XCTAssertTrue(project.waitForExistence(timeout: 15), "projects didn't load")
        project.tap()
        let sessionCell = app.staticTexts[title]
        XCTAssertTrue(sessionCell.waitForExistence(timeout: 15), "session row not found")
        sessionCell.tap()

        // The injected permission renders a dock with an Allow button.
        let allow = app.buttons["permission.allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10), "permission dock didn't appear")
        XCTAssertTrue(app.staticTexts["Run command: echo hello"].exists, "permission summary missing")

        allow.tap()

        // Optimistic dismissal removes the dock.
        XCTAssertTrue(waitForGone(allow, timeout: 8), "dock didn't dismiss after Allow")
    }

    // MARK: helpers

    private func waitForGone(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !element.exists { return true }
            usleep(200_000)
        }
        return !element.exists
    }

    private func serverReachable() async -> Bool {
        guard let url = URL(string: base + "/global/health") else { return false }
        var request = URLRequest(url: url); request.timeoutInterval = 5
        return (try? await URLSession.shared.data(for: request)) != nil
    }

    private func createSession(title: String) async throws {
        let encodedDir = dir.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? dir
        var request = URLRequest(url: try XCTUnwrap(URL(string: "\(base)/session?directory=\(encodedDir)")))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["title": title])
        let (_, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        XCTAssertTrue((200...299).contains(code), "create session failed: HTTP \(code)")
    }
}
