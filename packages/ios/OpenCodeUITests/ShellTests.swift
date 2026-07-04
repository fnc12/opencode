import XCTest

/// The pocket terminal's history must survive closing (Done) and reopening —
/// it's kept in a store, not the sheet's transient state. Needs a live server
/// (PAIR_LINK launch env), skips otherwise.
final class ShellTests: XCTestCase {
    func testShellHistorySurvivesDone() throws {
        guard let link = ProcessInfo.processInfo.environment["PAIR_LINK"], !link.isEmpty else {
            throw XCTSkip("PAIR_LINK not set — live server required")
        }
        let app = XCUIApplication()
        app.launchEnvironment["PAIR_LINK"] = link
        app.launch()

        XCTAssertTrue(app.staticTexts["sqlite2orm"].waitForExistence(timeout: 25))
        app.staticTexts["sqlite2orm"].tap()
        let cell = app.cells.firstMatch
        XCTAssertTrue(cell.waitForExistence(timeout: 15)); cell.tap()
        _ = app.textViews["composer.field"].waitForExistence(timeout: 20)

        app.buttons["session.shell"].tap()
        let field = app.textFields["shell.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 8))
        field.tap(); field.typeText("pwd"); app.buttons["shell.run"].tap()

        let ran = app.staticTexts["$ pwd"]
        XCTAssertTrue(ran.waitForExistence(timeout: 20), "command should run + record")

        app.buttons["Done"].tap()
        app.buttons["session.shell"].tap() // reopen

        XCTAssertTrue(app.staticTexts["$ pwd"].waitForExistence(timeout: 8),
                      "shell history must survive closing + reopening the terminal")
    }
}
