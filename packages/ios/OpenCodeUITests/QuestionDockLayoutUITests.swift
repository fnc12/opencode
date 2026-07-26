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

        // The deep options must be reachable by scrolling INSIDE the dock: the
        // last question's last option.
        let deepOption = app.buttons["Координаты (x,y) в комнате"]
        XCTAssertTrue(deepOption.exists, "deep option missing from the dock")

        // Auto-advance: answering a single-select question scrolls the next
        // unanswered one into view, so it's obvious why Submit is still
        // disabled. Walk the whole questionnaire to an enabled Submit.
        app.buttons["ESP32-S3 (8MB flash)"].firstMatch.tap()
        XCTAssertTrue(waitHittable(app.staticTexts["Где работает агрегатор"], timeout: 4),
                      "answering Q1 must scroll Q2 into view")

        app.buttons["Домашний laptop/RPi в той же WiFi-сети"].firstMatch.tap()
        XCTAssertTrue(waitHittable(app.staticTexts["Цель по точности"], timeout: 4),
                      "answering Q2 must scroll Q3 into view")

        let submit2 = app.buttons["question.submit"]
        XCTAssertFalse(submit2.isEnabled, "submit stays disabled until every question is answered")
        app.buttons["Presence по комнатам"].firstMatch.tap() // Q3 is multi-select
        XCTAssertTrue(submit2.isEnabled, "submit enables once all questions have an answer")

        // Keep a visual of the bounded dock in the test results.
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "question-dock-bounded"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Reading mode: with a pending question, scrolling up into history must
    /// collapse the dock to a one-line pill (it was covering the transcript);
    /// tapping the pill returns to the bottom where the full dock lives.
    func testDockCollapsesToPillWhileReadingHistory() async throws {
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

        // Full dock at the bottom.
        XCTAssertTrue(app.buttons["question.reject"].waitForExistence(timeout: 10), "dock didn't appear")

        // Scroll up into history → the dock must collapse to the pill.
        // (The tap-returns-full-dock half of the flow is covered by the Android
        // instrumented test: XCUITest's accessibility crawl on a session with
        // hundreds of rows is so slow it interferes with the list's scroll
        // state, making post-tap assertions here flaky by construction. The
        // return WAS verified on-screen via the failure-hierarchy dump.)
        app.tables.firstMatch.swipeDown()
        app.tables.firstMatch.swipeDown()
        let pill = app.buttons["question.pill"]
        XCTAssertTrue(pill.waitForExistence(timeout: 30), "dock must collapse to a pill while reading history")
    }

    private func waitHittable(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists && element.isHittable { return true }
            usleep(200_000)
        }
        return element.exists && element.isHittable
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
