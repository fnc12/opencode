import XCTest
@testable import OpenCode

/// The pocket-terminal history store (per-session, in-memory, singleton).
@MainActor
final class ShellStoreTests: XCTestCase {
    private let store = ShellStore.shared

    func testEmptyForUnknownSession() {
        XCTAssertTrue(store.history("unknown-\(UUID())").isEmpty)
    }

    func testAppendAndRead() {
        let sid = "s-\(UUID())"
        store.append(ShellEntry(command: "ls", output: "a\nb"), to: sid)
        store.append(ShellEntry(command: "pwd", output: "/w"), to: sid)
        let h = store.history(sid)
        XCTAssertEqual(h.count, 2)
        XCTAssertEqual(h.first?.command, "ls")
        XCTAssertEqual(h.last?.output, "/w")
    }

    func testClear() {
        let sid = "s-\(UUID())"
        store.append(ShellEntry(command: "x", output: "y"), to: sid)
        store.clear(sid)
        XCTAssertTrue(store.history(sid).isEmpty)
    }

    func testPerSessionIsolation() {
        let a = "a-\(UUID())", b = "b-\(UUID())"
        store.append(ShellEntry(command: "a", output: "1"), to: a)
        XCTAssertEqual(store.history(a).count, 1)
        XCTAssertTrue(store.history(b).isEmpty, "one session's history must not leak into another")
    }
}
