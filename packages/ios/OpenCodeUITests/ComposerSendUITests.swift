import XCTest

/// Drives the real UI on the simulator end-to-end: connect (Direct mode to a
/// live server), navigate to a session, type a prompt in the composer, send it,
/// and assert the message and the assistant's reply render. This is how the send
/// path (#6) is verified without a physical device.
///
/// Requires a reachable OpenCode server at `127.0.0.1:4096` (an SSH tunnel from
/// the Mac); skipped otherwise.
@MainActor
final class ComposerSendUITests: XCTestCase {
    private let base = "http://127.0.0.1:4096"
    private let dir = "/mnt/data/sources/sqlite_orm"
    private let projectName = "sqlite_orm"

    func testSendMessageFromComposer() async throws {
        let reachable = await serverReachable()
        try XCTSkipUnless(reachable, "live server not reachable at \(base); start the tunnel to run this UI test")

        let stamp = Int(Date().timeIntervalSince1970)
        let sessionTitle = "UITEST \(stamp)"
        try await createSession(title: sessionTitle)

        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET"]
        app.launch()

        // Connect via Direct mode to the tunnel.
        let direct = app.buttons["Direct"]
        XCTAssertTrue(direct.waitForExistence(timeout: 10), "Connect screen didn't appear")
        direct.tap()

        let urlField = app.textFields["connect.serverURL"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 5))
        urlField.tap()
        urlField.typeText(base)
        app.buttons["connect.button"].tap()

        // Projects → Sessions → the session we just created.
        let project = app.staticTexts[projectName]
        XCTAssertTrue(project.waitForExistence(timeout: 15), "projects didn't load")
        project.tap()

        let sessionCell = app.staticTexts[sessionTitle]
        XCTAssertTrue(sessionCell.waitForExistence(timeout: 15), "session row not found")
        sessionCell.tap()

        // Composer: type a prompt and send. (A vertical-axis TextField may be a
        // textField or a textView, so match by identifier across element types.)
        let field = app.descendants(matching: .any).matching(identifier: "composer.field").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "composer not shown")
        let message = "uitest ping \(stamp)"
        field.tap()
        field.typeText(message)

        let send = app.buttons["composer.send"]
        // The send button enables once a model has been auto-selected.
        await fulfillment(of: [expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: send)], timeout: 20)
        send.tap()

        // The user's message echoes back over the stream and renders.
        XCTAssertTrue(app.staticTexts[message].waitForExistence(timeout: 15), "sent message didn't appear")
        // The assistant responds — its role label (agent name) appears.
        XCTAssertTrue(app.staticTexts["build"].waitForExistence(timeout: 45), "assistant didn't reply")
    }

    // MARK: server helpers (run in the test process; the simulator reaches the Mac at 127.0.0.1)

    private func serverReachable() async -> Bool {
        guard let url = URL(string: base + "/global/health") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 5
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
