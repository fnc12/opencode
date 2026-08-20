import XCTest
@testable import OpenCode

/// Running-tools extraction against the captured wifi-densepose payload
/// (2026-07-26), enriched with a completed `read` and a running `bash`: the
/// "background processes" strip must list ONLY the running background tool
/// (bash) — excluding completed tools and the `question` tool (that one is
/// driven by the question dock, so it must not also appear in the strip).
final class RunningToolsTests: XCTestCase {
    private func fixture() -> [MessageWithParts] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures/running-tool.json")
        let data = try! Data(contentsOf: url)
        return try! JSONDecoder().decode([MessageWithParts].self, from: data)
    }

    func testExtractsOnlyRunningToolsFromUnfinishedTurn() {
        let running = RunningTools.extract(fixture())
        XCTAssertEqual(running.count, 1, "only the running background tool — not completed ones, not the question")
        XCTAssertEqual(running.first?.name, "bash")
        XCTAssertNotNil(running.first?.startedMS, "start time drives the elapsed label")
    }

    func testQuestionToolIsExcludedFromStrip() {
        // The `question` tool "runs" while waiting on the user; it belongs to the
        // question dock, not the running-processes strip (else it double-shows).
        XCTAssertFalse(RunningTools.extract(fixture()).contains { $0.name == "question" },
                       "the running question must not appear in the strip")
    }

    func testCompletedTurnHasNoRunningTools() {
        var messages = fixture()
        // The same payload with the turn marked complete must extract nothing:
        // leftover "running" states of a finished turn are stale, not live.
        messages = messages.filter { message in
            if case .assistant(let info) = message.info { return info.time.completed != nil }
            return true
        }
        XCTAssertTrue(RunningTools.extract(messages).isEmpty)
    }
}
