import StoreKit
import SwiftUI

/// The Shubat Pro paywall: StoreKit's subscription view (localized price and
/// period from the App Store, Terms of Use and Privacy Policy links) plus
/// Restore Purchases and the relay-link status.
struct PaywallView: View {
    var store: SubscriptionStore
    @Environment(\.dismiss) private var dismiss
    @State private var showManage = false

    var body: some View {
        NavigationStack {
            SubscriptionStoreView(productIDs: SubscriptionConfig.productIDs) {
                VStack(spacing: 10) {
                    Text("Shubat Pro")
                        .font(.system(size: 30, weight: .bold, design: .monospaced))
                    Text("Use the hosted relay to reach your OpenCode server from anywhere, with push notifications when a session finishes or needs approval.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
            }
            .storeButton(.hidden, for: .restorePurchases)
            .storeButton(.visible, for: .policies)
            .subscriptionStorePolicyDestination(url: SubscriptionConfig.termsURL, for: .termsOfService)
            .subscriptionStorePolicyDestination(url: SubscriptionConfig.privacyURL, for: .privacyPolicy)
            .onInAppPurchaseCompletion { _, result in
                if case .success(.success(let verification)) = result {
                    await store.handle(verification)
                }
            }
            .safeAreaInset(edge: .bottom) { footer }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .manageSubscriptionsSheet(isPresented: $showManage)
            .task { await store.refreshEntitlements() }
        }
    }

    @ViewBuilder private var footer: some View {
        VStack(spacing: 8) {
            if let expires = store.expiresAt, store.activeProductID != nil {
                Text("Active until \(expires.formatted(date: .abbreviated, time: .omitted))")
                    .font(.footnote)
                Button("Manage subscription") { showManage = true }
                    .font(.footnote)
            }
            Button {
                Task { await store.restore() }
            } label: {
                if store.restoring { ProgressView() } else { Text("Restore Purchases") }
            }
            .font(.footnote)
            .disabled(store.restoring)
            .accessibilityIdentifier("paywall.restore")
            if let error = store.syncError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            Text("Payment is charged to your Apple ID at confirmation of purchase. The subscription renews automatically unless cancelled at least 24 hours before the end of the current period. Manage or cancel it in your App Store account settings.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal)
        .padding(.bottom, 4)
        .background(.bar)
    }
}
