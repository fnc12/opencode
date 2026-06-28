import XCTest

/// Verifies the fresh-server onboarding path (#51): from the Projects screen,
/// "Open folder" → type a server directory → a new session is created and
/// opened (composer visible). Requires the tunnel; skipped otherwise.
@MainActor
final class OpenFolderUITests: XCTestCase {
    private let base = "http://127.0.0.1:4096"
    private let dir = "/mnt/data/sources/sqlite_orm"

    func testOpenFolderCreatesAndOpensSession() async throws {
        let reachable = await serverReachable()
        try XCTSkipUnless(reachable, "live server not reachable at \(base); start the tunnel to run this UI test")

        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET"]
        app.launch()

        let direct = app.buttons["Direct"]
        XCTAssertTrue(direct.waitForExistence(timeout: 10))
        direct.tap()
        let urlField = app.textFields["connect.serverURL"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 5))
        urlField.tap(); urlField.typeText(base)
        app.buttons["connect.button"].tap()

        // Projects screen → Open folder.
        let openFolder = app.buttons["projects.openFolder"]
        XCTAssertTrue(openFolder.waitForExistence(timeout: 15), "Projects screen / open-folder button missing")
        openFolder.tap()

        // The folder browser lists the root's subfolders.
        XCTAssertTrue(app.buttons["dir.mnt"].waitForExistence(timeout: 10), "folder browser didn't list root")

        // Type the target path (clearing the current "/" first) and open it.
        let pathField = app.textFields["openFolder.path"]
        XCTAssertTrue(pathField.waitForExistence(timeout: 5), "open-folder sheet missing")
        pathField.tap()
        if let current = pathField.value as? String {
            pathField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        pathField.typeText(dir)
        app.buttons["openFolder.create"].tap()

        // We should land in a freshly-created session (the composer is present).
        let composer = app.descendants(matching: .any).matching(identifier: "composer.field").firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 15), "did not open the new session's composer")
    }

    private func serverReachable() async -> Bool {
        guard let url = URL(string: base + "/global/health") else { return false }
        var request = URLRequest(url: url); request.timeoutInterval = 5
        return (try? await URLSession.shared.data(for: request)) != nil
    }
}
