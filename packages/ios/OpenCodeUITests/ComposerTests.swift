import XCTest

/// Guards the "composer disappears after dismissing a picker" regression: the
/// model + command pickers are sheets, and presenting a sheet from the
/// keyboard-hosted input accessory tore the composer down, leaving a blank
/// screen with only the keyboard. They're now presented from SessionView.
///
/// Needs a live server: set the PAIR_LINK launch env to an
/// `opencode://pair?...` link (skips otherwise).
final class ComposerTests: XCTestCase {
    private func openSession(_ app: XCUIApplication) throws {
        guard let link = ProcessInfo.processInfo.environment["PAIR_LINK"], !link.isEmpty else {
            throw XCTSkip("PAIR_LINK not set — live server required")
        }
        app.launchEnvironment["PAIR_LINK"] = link
        app.launch()
        let project = app.staticTexts["sqlite2orm"]
        XCTAssertTrue(project.waitForExistence(timeout: 25), "project list")
        project.tap()
        let cell = app.cells.firstMatch
        XCTAssertTrue(cell.waitForExistence(timeout: 15), "session list")
        cell.tap()
    }

    func testCommandSheetDismissalKeepsComposer() throws {
        let app = XCUIApplication()
        try openSession(app)

        let field = app.textViews["composer.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 20), "composer should be present")
        field.tap() // focus → keyboard up, matching the repro

        let commands = app.buttons["composer.commands"]
        guard commands.waitForExistence(timeout: 10) else {
            throw XCTSkip("server exposes no slash commands")
        }
        commands.tap()
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 5), "command sheet")
        cancel.tap()

        // The regression: the composer must survive the sheet dismissal.
        XCTAssertTrue(field.waitForExistence(timeout: 5),
                      "composer must remain after dismissing the command sheet")
        XCTAssertTrue(app.buttons["composer.send"].exists, "send button must remain")
    }

    func testPhotoPickerDismissalKeepsComposer() throws {
        let app = XCUIApplication()
        try openSession(app)

        let field = app.textViews["composer.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 20), "composer should be present")
        field.tap()

        app.buttons["composer.attach"].tap()
        let cancel = app.buttons["Cancel"]
        if cancel.waitForExistence(timeout: 6) { cancel.tap() } else { app.swipeDown() }

        XCTAssertTrue(field.waitForExistence(timeout: 5),
                      "composer must remain after dismissing the photo picker")
    }

    func testModelSheetDismissalKeepsComposer() throws {
        let app = XCUIApplication()
        try openSession(app)

        let field = app.textViews["composer.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 20), "composer should be present")
        field.tap()

        let model = app.buttons["composer.model"]
        guard model.waitForExistence(timeout: 10) else {
            throw XCTSkip("model button not found")
        }
        model.tap()
        let done = app.buttons["Done"]
        if done.waitForExistence(timeout: 5) { done.tap() } else {
            app.swipeDown() // fallback
        }
        XCTAssertTrue(field.waitForExistence(timeout: 5),
                      "composer must remain after dismissing the model sheet")
    }
}
