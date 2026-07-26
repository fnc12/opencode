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

        // …with the escape hatches ON SCREEN despite the huge content. This is
        // the bug: pre-fix the dock had no height bound or scrolling, so both
        // buttons sat far below the screen edge.
        let reject = app.buttons["question.reject"]
        let submit = app.buttons["question.submit"]
        XCTAssertTrue(reject.exists, "Skip button missing")
        XCTAssertTrue(reject.isHittable, "Skip is off-screen — the user can't escape the dialog")
        XCTAssertTrue(submit.exists, "Submit button missing")
        XCTAssertTrue(submit.isHittable, "Submit is off-screen")

        // Every option is present in the dock (the whole questionnaire is laid out
        // as one tall row; the LIST scrolls to reach the deep ones). Check the last
        // question's last option exists.
        let deepOption = app.buttons["Координаты (x,y) в комнате"]
        XCTAssertTrue(deepOption.exists, "deep option missing from the dock")

        // Answer every question — the questionnaire is taller than the screen, so
        // reveal the top first, then walk down (tapping auto-scrolls to the lower
        // ones). Submit only enables once all three have an answer.
        let submit2 = app.buttons["question.submit"]
        let q1 = app.buttons["ESP32-S3 (8MB flash)"].firstMatch
        reveal(app, q1)
        q1.tap()
        XCTAssertFalse(submit2.isEnabled, "submit stays disabled until every question is answered")
        let q2 = app.buttons["Домашний laptop/RPi в той же WiFi-сети"].firstMatch
        reveal(app, q2)
        q2.tap()
        XCTAssertFalse(submit2.isEnabled, "submit stays disabled until every question is answered")
        let q3 = app.buttons["Presence по комнатам"].firstMatch // Q3 is multi-select
        reveal(app, q3)
        q3.tap()
        XCTAssertTrue(submit2.isEnabled, "submit enables once all questions have an answer")

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
        app.tables.firstMatch.swipeDown()
        app.tables.firstMatch.swipeDown()
        XCTAssertTrue(waitUnhittable(reject, timeout: 8),
                      "dock must scroll away with the transcript, not stay pinned over it")
        attach(app, "reading-history-dock-gone")

        // Scroll back to the newest message: the dock returns with the content.
        for _ in 0..<15 {
            if reject.exists && reject.isHittable { break }
            app.tables.firstMatch.swipeUp(velocity: .fast)
        }
        XCTAssertTrue(waitHittable(reject, timeout: 8), "dock must return at the newest message")
        attach(app, "back-at-newest-message")
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }

    /// Scroll the list until `element` is on-screen and hittable, moving toward it
    /// (up or down) based on where its frame currently sits.
    private func reveal(_ app: XCUIApplication, _ element: XCUIElement, attempts: Int = 12) {
        let table = app.tables.firstMatch
        let screenH = app.windows.firstMatch.frame.height
        for _ in 0..<attempts {
            if element.exists && element.isHittable { return }
            guard element.exists else { table.swipeUp(); continue }
            if element.frame.midY < screenH * 0.2 { table.swipeDown() } // above viewport → scroll up
            else { table.swipeUp() }                                    // below viewport → scroll down
        }
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
