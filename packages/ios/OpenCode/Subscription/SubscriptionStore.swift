import Foundation
import Observation
import StoreKit

/// Tracks the Shubat Pro subscription on this device and forwards every verified
/// transaction's signed JWS to the relay, which binds it to the paired account.
/// Access itself is enforced by the relay; this only reports and syncs state.
@MainActor
@Observable
final class SubscriptionStore {
    /// The active subscription, if any (verified, not revoked, not expired).
    private(set) var activeProductID: String?
    private(set) var expiresAt: Date?
    /// Entitlement expiry as reported by the relay after the last upload.
    private(set) var relayEntitledUntil: Date?
    /// Last relay upload or restore failure, shown on the paywall.
    private(set) var syncError: String?
    private(set) var restoring = false

    /// Where verified transactions are uploaded; nil outside relay mode.
    private(set) var relay: RelayIAPClient?

    @ObservationIgnored private let productIDs: Set<String>
    /// Latest JWS not yet accepted by the relay (no relay yet, or upload failed).
    @ObservationIgnored private var pendingJWS: String?
    @ObservationIgnored private var updates: Task<Void, Never>?

    init(productIDs: [String] = SubscriptionConfig.productIDs) {
        self.productIDs = Set(productIDs)
    }

    /// Listens for transactions made outside the purchase button — renewals,
    /// Ask to Buy approvals, purchases on another device. Call once at launch.
    func startListening() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                await self?.handle(result)
            }
        }
    }

    /// Points uploads at the current relay connection and flushes a pending JWS.
    func setRelay(_ client: RelayIAPClient?) async {
        relay = client
        if let jws = pendingJWS { await upload(jws) }
    }

    /// Handles a transaction from a purchase, a restore or `Transaction.updates`.
    func handle(_ result: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = result,
              productIDs.contains(transaction.productID) else { return }
        apply(transaction)
        await upload(result.jwsRepresentation)
        await transaction.finish()
    }

    /// Restore Purchases: re-syncs with the App Store, then re-reads entitlements.
    func restore() async {
        restoring = true
        defer { restoring = false }
        do {
            try await AppStore.sync()
            syncError = nil
        } catch StoreKitError.userCancelled {
            return
        } catch {
            syncError = "Restore failed: \(error.localizedDescription)"
            return
        }
        await refreshEntitlements()
    }

    /// Re-reads the current entitlements and uploads the active one, so the relay
    /// catches up with renewals it hasn't seen.
    func refreshEntitlements() async {
        var latest: (Transaction, String)?
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  productIDs.contains(transaction.productID),
                  transaction.revocationDate == nil else { continue }
            if latest == nil || transaction.purchaseDate > latest!.0.purchaseDate {
                latest = (transaction, result.jwsRepresentation)
            }
        }
        guard let (transaction, jws) = latest else {
            activeProductID = nil
            expiresAt = nil
            return
        }
        apply(transaction)
        await upload(jws)
    }

    private func apply(_ transaction: Transaction) {
        if Self.isActive(revoked: transaction.revocationDate != nil,
                         expires: transaction.expirationDate, now: Date()) {
            activeProductID = transaction.productID
            expiresAt = transaction.expirationDate
        } else if activeProductID == transaction.productID {
            activeProductID = nil
            expiresAt = nil
        }
    }

    private func upload(_ jws: String) async {
        guard let relay else { pendingJWS = jws; return }
        do {
            relayEntitledUntil = try await relay.upload(signedTransaction: jws)
            pendingJWS = nil
            syncError = nil
        } catch {
            pendingJWS = jws
            syncError = "Couldn't link the subscription to your relay account. It will retry on the next launch."
        }
    }

    /// A subscription transaction grants access while not revoked and not past
    /// its expiry. Pure, so the rule is unit-testable.
    nonisolated static func isActive(revoked: Bool, expires: Date?, now: Date) -> Bool {
        guard !revoked else { return false }
        guard let expires else { return true }
        return expires > now
    }
}
