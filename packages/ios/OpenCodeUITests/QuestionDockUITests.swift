import XCTest

/// Drives the question dock on the simulator: open a session with an injected
/// pending question (`UITEST_QUESTION`), pick an option, submit, and assert the
/// dock dismisses. Requires the tunnel for connect→navigate; skipped otherwise.
@MainActor
final class QuestionDockUITests: XCTestCase {
    private let base = "http://127.0.0.1:4096"
    private let dir = "/mnt/data/sources/sqlite_orm"
    private let projectName = "sqlite_orm"
    /// Server password from the runner env (public repo — no hardcoded creds).
    private let password = ProcessInfo.processInfo.environment["OPENCODE_TEST_PASSWORD"]

    func testAnswerQuestionFromDock() async throws {
        let reachable = await serverReachable()
        try XCTSkipUnless(reachable, "live server not reachable at \(base); start the tunnel to run this UI test")

        let stamp = Int(Date().timeIntervalSince1970)
        let title = "QTEST \(stamp)"
        try await createSession(title: title)

        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET", "UITEST_QUESTION"]
        app.launch()

        let direct = app.buttons["Direct"]
        XCTAssertTrue(direct.waitForExistence(timeout: 10))
        direct.tap()
        let urlField = app.textFields["connect.serverURL"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 5))
        urlField.tap(); urlField.typeText(base)
        if let password {
            let pwField = app.secureTextFields["connect.password"]
            XCTAssertTrue(pwField.waitForExistence(timeout: 5))
            pwField.tap(); pwField.typeText(password)
        }
        app.buttons["connect.button"].tap()

        let project = app.staticTexts[projectName]
        XCTAssertTrue(project.waitForExistence(timeout: 15), "projects didn't load")
        project.tap()
        let sessionCell = app.staticTexts[title]
        XCTAssertTrue(sessionCell.waitForExistence(timeout: 15), "session row not found")
        sessionCell.tap()

        // The injected question renders a dock with the question and options.
        XCTAssertTrue(app.staticTexts["Which database?"].waitForExistence(timeout: 10), "question dock didn't appear")
        let optionA = app.buttons["Option A"]
        XCTAssertTrue(optionA.waitForExistence(timeout: 3))
        optionA.tap()

        let submit = app.buttons["question.submit"]
        XCTAssertTrue(submit.isEnabled, "submit should enable after selecting an option")
        submit.tap()

        XCTAssertTrue(waitForGone(app.buttons["question.submit"], timeout: 8), "dock didn't dismiss after submit")
    }

    private func waitForGone(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline { if !element.exists { return true }; usleep(200_000) }
        return !element.exists
    }

    private func serverReachable() async -> Bool {
        guard let url = URL(string: base + "/global/health") else { return false }
        var request = URLRequest(url: url); request.timeoutInterval = 5
        if let password {
            request.setValue("Basic " + Data("opencode:\(password)".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
        }
        return (try? await URLSession.shared.data(for: request)) != nil
    }

    private func createSession(title: String) async throws {
        let encodedDir = dir.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? dir
        var request = URLRequest(url: try XCTUnwrap(URL(string: "\(base)/session?directory=\(encodedDir)")))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let password {
            request.setValue("Basic " + Data("opencode:\(password)".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: ["title": title])
        let (_, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        XCTAssertTrue((200...299).contains(code), "create session failed: HTTP \(code)")
    }
}
