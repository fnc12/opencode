import XCTest

/// The "thinking" indicator (three dots) must appear in the message flow — right
/// after the last message, where the reply will stream in — not as a fixed
/// overlay that lands on top of an existing message. UITEST_TYPING forces it on
/// so the placement is deterministic. Needs a live server (PAIR_LINK).
final class StreamingTests: XCTestCase {
    func testTypingIndicatorSitsBelowLastMessage() throws {
        guard let link = ProcessInfo.processInfo.environment["PAIR_LINK"], !link.isEmpty else {
            throw XCTSkip("PAIR_LINK not set — live server required")
        }
        let app = XCUIApplication()
        app.launchEnvironment["PAIR_LINK"] = link
        app.launchEnvironment["UITEST_TYPING"] = "1"
        app.launch()

        XCTAssertTrue(app.staticTexts["sqlite2orm"].waitForExistence(timeout: 25))
        app.staticTexts["sqlite2orm"].tap()
        let session = app.cells.firstMatch
        XCTAssertTrue(session.waitForExistence(timeout: 15)); session.tap()
        XCTAssertTrue(app.textViews["composer.field"].waitForExistence(timeout: 25))

        let indicator = app.descendants(matching: .any)["typing.indicator"]
        XCTAssertTrue(indicator.waitForExistence(timeout: 8), "typing indicator should show")

        // It's the table's footer, so it must sit below the last message cell —
        // not overlapping it like the old fixed-offset overlay did.
        let cells = app.cells
        let lastCell = cells.element(boundBy: cells.count - 1)
        XCTAssertTrue(lastCell.exists, "there should be at least one message")
        XCTAssertGreaterThan(indicator.frame.minY, lastCell.frame.minY,
                             "typing indicator must be below the last message, not on top of it")
    }
}
