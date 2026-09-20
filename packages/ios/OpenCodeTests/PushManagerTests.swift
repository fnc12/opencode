import XCTest
import UserNotifications
@testable import OpenCode

/// Covers PushManager's testable statics + instance callbacks: token decoding,
/// tap routing (live handler vs cold-launch parking), and the foreground
/// banner-suppression rule. The two UN delegate methods that need a real
/// `UNNotification`/`UNNotificationResponse` (uninitializable in a test) are
/// covered via the extracted pure helper `presentationOptions(forSessionID:)`.
@MainActor
final class PushManagerTests: XCTestCase {
    override func setUp() {
        super.setUp()
        // The statics persist across tests — reset the ones we touch.
        PushManager.onToken = nil
        PushManager.onOpenSession = nil
        PushManager.activeSessionID = nil
        // Flush any parked pending id by installing (then clearing) a handler.
        PushManager.setOpenSessionHandler { _ in }
        PushManager.onOpenSession = nil
    }

    // --- device token ---------------------------------------------------------

    func testDeviceTokenIsHexEncodedAndPublished() {
        var delivered: String?
        PushManager.onToken = { delivered = $0 }
        let pm = PushManager()
        pm.application(UIApplication.shared,
                       didRegisterForRemoteNotificationsWithDeviceToken: Data([0x01, 0xab, 0xff, 0x00]))
        XCTAssertEqual(PushManager.lastToken, "01abff00")
        XCTAssertEqual(delivered, "01abff00")
    }

    func testFailToRegisterIsANoOp() {
        // The simulator branch: nothing crashes, nothing published.
        let pm = PushManager()
        struct E: Error {}
        pm.application(UIApplication.shared, didFailToRegisterForRemoteNotificationsWithError: E())
    }

    func testDidFinishLaunchingSetsDelegate() {
        let pm = PushManager()
        _ = pm.application(UIApplication.shared, didFinishLaunchingWithOptions: nil)
        XCTAssertTrue(UNUserNotificationCenter.current().delegate === pm)
    }

    // --- tap routing ----------------------------------------------------------

    func testRouteOpenDeliversToLiveHandler() {
        var opened: String?
        PushManager.setOpenSessionHandler { opened = $0 }
        PushManager.routeOpen("ses_live")
        XCTAssertEqual(opened, "ses_live")
    }

    func testRouteOpenParksUntilHandlerSet() {
        // No handler yet (cold launch) → the id is parked, then flushed when the
        // handler is installed.
        PushManager.routeOpen("ses_cold")
        var opened: String?
        PushManager.setOpenSessionHandler { opened = $0 }
        XCTAssertEqual(opened, "ses_cold")
    }

    // --- foreground banner suppression ---------------------------------------

    func testForegroundBannerSuppressedForActiveSession() {
        PushManager.activeSessionID = "ses_1"
        XCTAssertEqual(PushManager.presentationOptions(forSessionID: "ses_1"), [])
    }

    func testForegroundBannerShownForOtherSession() {
        PushManager.activeSessionID = "ses_1"
        XCTAssertEqual(PushManager.presentationOptions(forSessionID: "ses_2"), [.banner, .sound])
    }

    func testForegroundBannerShownWhenNoSessionID() {
        PushManager.activeSessionID = "ses_1"
        XCTAssertEqual(PushManager.presentationOptions(forSessionID: nil), [.banner, .sound])
    }
}
