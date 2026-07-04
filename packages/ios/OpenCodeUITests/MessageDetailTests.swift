import XCTest

/// Opening a message's detail and closing it repeatedly used to sometimes drop
/// the whole session into an "Error: cancelled" screen (the message fetch's task
/// was cancelled when the detail was presented over it), and flashed a blank
/// screen on dismiss. The detail is now presented `.overFullScreen` (list stays
/// behind) and `run()` ignores cancellation.
///
/// Needs a live server (PAIR_LINK launch env), skips otherwise.
final class MessageDetailTests: XCTestCase {
    func testRapidDetailOpenCloseKeepsList() throws {
        guard let link = ProcessInfo.processInfo.environment["PAIR_LINK"], !link.isEmpty else {
            throw XCTSkip("PAIR_LINK not set — live server required")
        }
        let app = XCUIApplication()
        app.launchEnvironment["PAIR_LINK"] = link
        app.launch()

        XCTAssertTrue(app.staticTexts["sqlite2orm"].waitForExistence(timeout: 25))
        app.staticTexts["sqlite2orm"].tap()
        let session = app.cells.firstMatch
        XCTAssertTrue(session.waitForExistence(timeout: 15)); session.tap()

        let field = app.textViews["composer.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 25), "session loaded")

        // Open + close a message's detail several times in a row.
        for _ in 0..<6 {
            let cell = app.cells.firstMatch
            XCTAssertTrue(cell.waitForExistence(timeout: 5))
            cell.tap()
            let done = app.buttons["Done"]
            XCTAssertTrue(done.waitForExistence(timeout: 5), "detail should open")
            done.tap()
            // Never fall into the error screen.
            XCTAssertFalse(app.staticTexts["Error"].exists,
                           "session must not drop into an Error screen on detail dismissal")
        }

        // The list + composer are still there (not a blank / error screen).
        XCTAssertTrue(field.waitForExistence(timeout: 5), "composer + message list must remain")
        XCTAssertFalse(app.staticTexts["Error"].exists, "no error screen after repeated open/close")
    }
}
