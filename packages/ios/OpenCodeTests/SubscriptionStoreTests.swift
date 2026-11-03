import StoreKit
import StoreKitTest
import XCTest
@testable import OpenCode

/// Shubat Pro against the local StoreKit catalog (`StoreKit/Shubat.storekit`):
/// the product loads with its store price, and a purchase is reflected in the
/// store's state and uploaded to the relay as a signed JWS.
@MainActor
final class SubscriptionStoreTests: XCTestCase {
    private var session: SKTestSession!

    override func setUp() async throws {
        try await super.setUp()
        StubURLProtocol.reset()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Shubat", withExtension: "storekit"))
        session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.clearTransactions()
        session.disableDialogs = true
    }

    override func tearDown() async throws {
        session?.clearTransactions()
        session = nil
        try await super.tearDown()
    }

    func testCatalogMatchesConfiguredProduct() async throws {
        let products = try await Product.products(for: SubscriptionConfig.productIDs)
        let product = try XCTUnwrap(products.first)
        XCTAssertEqual(products.count, 1)
        XCTAssertEqual(product.type, .autoRenewable)
        XCTAssertEqual(product.price, Decimal(string: "4.99"))
        XCTAssertEqual(product.subscription?.subscriptionPeriod.unit, .month)
    }

    func testPurchaseIsActiveAndUploadedToRelay() async throws {
        let id = try XCTUnwrap(SubscriptionConfig.productIDs.first)
        _ = try await session.buyProduct(identifier: id)

        StubURLProtocol.enqueue(200, body: #"{"entitledUntil":1800000000}"#)
        let store = SubscriptionStore()
        await store.setRelay(RelayIAPClient(relayURL: "https://relay.stub", tunnelID: "tun_abc",
                                            token: "secret", session: StubURLProtocol.session()))
        await store.refreshEntitlements()

        XCTAssertEqual(store.activeProductID, id)
        XCTAssertNotNil(store.expiresAt)
        XCTAssertEqual(store.relayEntitledUntil, Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertNil(store.syncError)
        let upload = try XCTUnwrap(StubURLProtocol.seen.last)
        XCTAssertEqual(upload.url?.path, "/api/iap/apple")
        XCTAssertEqual(upload.value(forHTTPHeaderField: "X-Tunnel-Token"), "secret")
    }

    func testPurchaseBeforePairingIsUploadedOnceRelayIsSet() async throws {
        let id = try XCTUnwrap(SubscriptionConfig.productIDs.first)
        _ = try await session.buyProduct(identifier: id)

        let store = SubscriptionStore()
        await store.refreshEntitlements()
        XCTAssertEqual(store.activeProductID, id)
        XCTAssertTrue(StubURLProtocol.seen.isEmpty)

        await store.setRelay(RelayIAPClient(relayURL: "https://relay.stub", tunnelID: "tun_abc",
                                            token: "secret", session: StubURLProtocol.session()))
        XCTAssertEqual(StubURLProtocol.seen.count, 1)
    }

    func testFailedUploadIsReported() async throws {
        let id = try XCTUnwrap(SubscriptionConfig.productIDs.first)
        _ = try await session.buyProduct(identifier: id)

        StubURLProtocol.enqueue(500)
        let store = SubscriptionStore()
        await store.setRelay(RelayIAPClient(relayURL: "https://relay.stub", tunnelID: "tun_abc",
                                            token: "secret", session: StubURLProtocol.session()))
        await store.refreshEntitlements()
        XCTAssertEqual(store.activeProductID, id)
        XCTAssertNotNil(store.syncError)
    }

    func testNoPurchaseMeansNoEntitlement() async {
        let store = SubscriptionStore()
        await store.refreshEntitlements()
        XCTAssertNil(store.activeProductID)
        XCTAssertNil(store.expiresAt)
    }

    func testActiveRule() {
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertTrue(SubscriptionStore.isActive(revoked: false, expires: now.addingTimeInterval(60), now: now))
        XCTAssertFalse(SubscriptionStore.isActive(revoked: false, expires: now.addingTimeInterval(-60), now: now))
        XCTAssertFalse(SubscriptionStore.isActive(revoked: true, expires: now.addingTimeInterval(60), now: now))
        XCTAssertTrue(SubscriptionStore.isActive(revoked: false, expires: nil, now: now))
    }
}
