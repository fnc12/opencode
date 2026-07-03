import UIKit
import UserNotifications

/// Registers the device for APNs and surfaces the token so it can be posted to
/// the relay (`/api/devices`). The relay pushes when a session goes idle.
final class PushManager: NSObject, UIApplicationDelegate {
    /// Called with the hex device token once APNs returns it.
    static var onToken: ((String) -> Void)?
    /// The most recent token, so registration can be retried after connecting.
    static private(set) var lastToken: String?

    static func requestAndRegister() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
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
}
