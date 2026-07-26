import XCTest
@testable import OpenCode

/// Running-tools extraction against the REAL captured payload (wifi-densepose,
/// 2026-07-26): an unfinished assistant turn with completed bash/read tools and
/// one RUNNING `question` tool. The strip must list only the running one.
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
        XCTAssertEqual(running.count, 1, "only the running tool, not completed ones")
        XCTAssertEqual(running.first?.name, "question")
        XCTAssertNotNil(running.first?.startedMS, "start time drives the elapsed label")
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
