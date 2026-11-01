import Foundation

/// Static configuration of the Shubat Pro auto-renewable subscription.
/// The product ids must match App Store Connect (and `Shubat.storekit` for
/// local testing); the real ids are set up in App Store Connect separately.
enum SubscriptionConfig {
    /// Auto-renewable subscription products offered on the paywall.
    static let productIDs: [String] = ["studio.eugenezakharov.shubat.pro.monthly"]

    /// Terms of Use: Apple's standard EULA (shown on the paywall, required by
    /// App Review for auto-renewable subscriptions).
    static let termsURL = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    static let privacyURL = URL(string: "https://shubat.org/privacy.html")!
}
