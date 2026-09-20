import XCTest

/// Reproduces the "question dialog you can't escape" bug with the REAL captured
/// payload (see `QuestionFixtures.wifiDensepose`): a 3-question / 11-option
/// request rendered the dock taller than the screen with no scrolling, pushing
/// Skip/Submit off-screen — the user was stuck. The dock must keep its action
/// row reachable no matter how big the questionnaire is.
@MainActor
final class QuestionDockLayoutUITests: XCTestCase {
    private let base = "http://127.0.0.1:4096"
    private let dir = "/mnt/data/sources/sqlite_orm"
    private let projectName = "sqlite_orm"
    /// Server password from the runner env (public repo — no hardcoded creds):
    /// TEST_RUNNER_OPENCODE_TEST_PASSWORD=…
    private let password = ProcessInfo.processInfo.environment["OPENCODE_TEST_PASSWORD"]

    func testHugeRealQuestionKeepsActionsReachable() async throws {
        let reachable = await serverReachable()
        try XCTSkipUnless(reachable, "live server not reachable at \(base); start the tunnel to run this UI test")

        let stamp = Int(Date().timeIntervalSince1970)
        let title = "QLAYOUT \(stamp)"
        try await createSession(title: title)

        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET"]
        app.launchEnvironment["UITEST_QUESTION_JSON"] = QuestionFixtures.wifiDensepose
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

        // A fresh session has no file changes — the diff button must be hidden.
        XCTAssertFalse(app.buttons["session.diff"].exists, "diff button must hide when there is no diff")

        // The real payload's dock must appear…
        let firstHeader = app.staticTexts["Модель ESP32"]
        XCTAssertTrue(firstHeader.waitForExistence(timeout: 10), "question dock didn't appear")

        // The escape hatches (Skip/Submit) stay pinned below the slides, ON SCREEN,
        // no matter how big the questionnaire is — the "can't escape the dialog" bug.
        let reject = app.buttons["question.reject"]
        let submit = app.buttons["question.submit"]
        XCTAssertTrue(reject.isHittable, "Skip is off-screen — the user can't escape the dialog")
        XCTAssertTrue(submit.isHittable, "Submit is off-screen")

        // Multi-question requests are SLIDES: answer each question and a single-select
        // pick auto-advances to the next one. Walk all three to an enabled Submit.
        app.buttons["ESP32-S3 (8MB flash)"].firstMatch.tap()
        XCTAssertTrue(waitHittable(app.buttons["Домашний laptop/RPi в той же WiFi-сети"], timeout: 4),
                      "answering Q1 must slide to Q2")
        XCTAssertFalse(submit.isEnabled, "submit stays disabled until every question is answered")
        app.buttons["Домашний laptop/RPi в той же WiFi-сети"].firstMatch.tap()
        XCTAssertTrue(waitHittable(app.buttons["Presence по комнатам"], timeout: 4),
                      "answering Q2 must slide to Q3")
        app.buttons["Presence по комнатам"].firstMatch.tap() // Q3 is multi-select (last slide)
        XCTAssertTrue(submit.isEnabled, "submit enables once all questions have an answer")

        // Keep a visual of the bounded dock in the test results.
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "question-dock-bounded"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// The question dock RIDES THE TRANSCRIPT as its footer, in step with the
    /// content — it is NOT a fixed overlay the messages slide under (which drew
    /// text-on-text). So: reachable at the newest message; scroll up into history
    /// and it leaves the screen together with the content's end; scroll back and
    /// it returns. A fixed overlay would stay put and keep overlapping.
    func testDockRidesTranscriptAsFooterNotFixedOverlay() async throws {
        let reachable = await serverReachable()
        try XCTSkipUnless(reachable, "live server not reachable at \(base); start the tunnel to run this UI test")

        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET"]
        app.launchEnvironment["UITEST_QUESTION_JSON"] = QuestionFixtures.wifiDensepose
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

        // A session with real history to read (the fixture question rides along).
        let project = app.staticTexts["sqlite2orm"]
        XCTAssertTrue(project.waitForExistence(timeout: 15), "projects didn't load")
        project.tap()
        let sessionCell = app.staticTexts["Описание проекта"]
        XCTAssertTrue(sessionCell.waitForExistence(timeout: 15))
        sessionCell.tap()

        // The dock is reachable at the newest message (it's the transcript's last row).
        let reject = app.buttons["question.reject"]
        XCTAssertTrue(reject.waitForExistence(timeout: 10), "dock didn't appear")
        XCTAssertTrue(waitHittable(reject, timeout: 5), "dock must be reachable at the newest message")
        attach(app, "footer-at-newest-message")

        // Scroll UP into history: the dock rides the content's END off the screen.
        // A FIXED overlay would stay pinned and keep covering the transcript — the
        // bug in the screenshots. Here it must leave WITH the content.
        scrollTranscript(app, toHistory: true)
        scrollTranscript(app, toHistory: true)
        XCTAssertTrue(waitUnhittable(reject, timeout: 8),
                      "dock must scroll away with the transcript, not stay pinned over it")
        attach(app, "reading-history-dock-gone")

        // Scroll back to the newest message: the dock returns with the content.
        for _ in 0..<24 {
            if reject.exists && reject.isHittable { break }
            scrollTranscript(app, toHistory: false)
        }
        XCTAssertTrue(waitHittable(reject, timeout: 8), "dock must return at the newest message")
        attach(app, "back-at-newest-message")
    }

    /// Drag the transcript in the UPPER (message) region — never over the dock at
    /// the bottom, whose slide pager would otherwise swallow a near-vertical swipe.
    /// toHistory=true reveals older messages; false returns toward the newest.
    private func scrollTranscript(_ app: XCUIApplication, toHistory: Bool) {
        let win = app.windows.firstMatch
        let hi = win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.22))
        let lo = win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.52))
        if toHistory { hi.press(forDuration: 0.02, thenDragTo: lo) } // finger down → older
        else { lo.press(forDuration: 0.02, thenDragTo: hi) }          // finger up → newer
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }

    private func waitHittable(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists && element.isHittable { return true }
            usleep(200_000)
        }
        return element.exists && element.isHittable
    }

    private func waitUnhittable(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !element.exists || !element.isHittable { return true }
            usleep(200_000)
        }
        return !element.exists || !element.isHittable
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
