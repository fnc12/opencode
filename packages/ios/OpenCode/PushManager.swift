import UIKit
import UserNotifications

/// Registers the device for APNs, surfaces the token so it can be posted to the
/// relay (`/api/devices`), and routes notification taps to the right session.
/// The relay pushes when a session goes idle or the agent needs a permission.
final class PushManager: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Called with the hex device token once APNs returns it.
    static var onToken: ((String) -> Void)?
    /// The most recent token, so registration can be retried after connecting.
    static private(set) var lastToken: String?
    /// Called with a session id when the user taps a notification.
    @MainActor static var onOpenSession: ((String) -> Void)?
    /// Holds a tapped session id that arrived before a handler was wired (e.g. a
    /// cold launch from a notification). Delivered as soon as `onOpenSession` is set.
    @MainActor static private var pendingSessionID: String?

    /// Set the handler and immediately flush any tap captured before it existed.
    @MainActor static func setOpenSessionHandler(_ handler: @escaping (String) -> Void) {
        onOpenSession = handler
        if let sid = pendingSessionID {
            pendingSessionID = nil
            handler(sid)
        }
    }

    /// Route a tapped session id: hand it to the live handler, or park it until
    /// one is set (cold launch). Main-actor isolated — touches the statics above.
    @MainActor static func routeOpen(_ sessionID: String) {
        if let handler = onOpenSession {
            handler(sessionID)
        } else {
            pendingSessionID = sessionID
        }
    }

    static func requestAndRegister() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Receive notification taps (and foreground presentation) via this delegate.
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        PushManager.lastToken = hex
        PushManager.onToken?(hex)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // The simulator has no APNs; nothing to do.
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// User tapped a notification — open the session it refers to.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let sid = response.notification.request.content.userInfo["sessionId"] as? String
        if let sid, !sid.isEmpty {
            Task { @MainActor in PushManager.routeOpen(sid) }
        }
        completionHandler()
    }

    /// Show the banner even while the app is in the foreground.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
