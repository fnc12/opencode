import XCTest
@testable import OpenCode

/// The on-disk newest-page cache: a save→load round-trip decodes back through the
/// production model path, and a miss is nil (not a crash).
final class MessageCacheTests: XCTestCase {
    private let sessionID = "ses_cache_test_\(UUID().uuidString)"

    private func rawArray(ids: [(String, Double)]) -> Data {
        let objs = ids.map { id, created in
            """
            {"info":{"id":"\(id)","sessionID":"\(sessionID)","role":"user","time":{"created":\(created)}},
            "parts":[{"id":"prt_\(id)","sessionID":"\(sessionID)","messageID":"\(id)","type":"text","text":"hi"}]}
            """
        }
        return Data("[\(objs.joined(separator: ","))]".utf8)
    }

    func testSaveThenLoadRoundTrips() {
        MessageCache.save(sessionID, raw: rawArray(ids: [("m1", 1), ("m2", 2)]))
        let loaded = MessageCache.load(sessionID)
        XCTAssertEqual(loaded?.map(\.id), ["m1", "m2"], "cached newest page decodes back through the model path")
    }

    func testMissReturnsNil() {
        XCTAssertNil(MessageCache.load("ses_never_written_\(UUID().uuidString)"))
    }

    func testSaveOverwritesPreviousPage() {
        MessageCache.save(sessionID, raw: rawArray(ids: [("old", 1)]))
        MessageCache.save(sessionID, raw: rawArray(ids: [("new", 2)]))
        XCTAssertEqual(MessageCache.load(sessionID)?.map(\.id), ["new"], "a fresh save replaces the prior page")
    }
}
