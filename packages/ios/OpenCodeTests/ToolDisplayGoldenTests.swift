import XCTest
@testable import OpenCode

/// Golden tests over a REAL session captured from a live OpenCode server
/// (sqlite2orm, fetched via the relay). They assert the native client renders
/// tool / patch / file parts the same compact way the web client's
/// `getToolInfo()` does — and, crucially, that it never dumps raw tool output
/// (the bug that showed whole files and build logs inline).
final class ToolDisplayGoldenTests: XCTestCase {
    /// The captured session, decoded once per test instance.
    private lazy var messages: [MessageWithParts] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures/session-sqlite2orm.json")
        guard let data = try? Data(contentsOf: url) else {
            fatalError("fixture not found at \(url.path)")
        }
        do {
            return try JSONDecoder().decode([MessageWithParts].self, from: data)
        } catch {
            fatalError("fixture failed to decode: \(error)")
        }
    }()

    private func firstTool(_ name: String) -> ToolContent {
        for m in messages {
            for p in m.parts {
                if case .tool(let t) = p.content, t.tool == name { return t }
            }
        }
        fatalError("no \(name) tool in fixture")
    }

    private func firstPart<T>(_ extract: (PartContent) -> T?) -> T {
        for m in messages {
            for p in m.parts {
                if let c = p.content, let v = extract(c) { return v }
            }
        }
        fatalError("part not found")
    }

    // MARK: the real session decodes at all

    func testFixtureDecodes() {
        XCTAssertGreaterThan(messages.count, 0)
        let parts = messages.flatMap(\.parts).compactMap(\.content)
        XCTAssertTrue(parts.contains { if case .tool = $0 { return true }; return false })
        XCTAssertTrue(parts.contains { if case .patch = $0 { return true }; return false })
        XCTAssertTrue(parts.contains { if case .file = $0 { return true }; return false })
    }

    // MARK: tool rows match the web client's labels + targets

    func testReadShowsFilenameOnly_neverDumpsContent() {
        let (label, detail) = ToolDisplay.describe(firstTool("read"))
        XCTAssertEqual(label, "Read")
        if let detail {
            // The old bug rendered the whole file as `<path>…<content>286:…`.
            XCTAssertFalse(detail.contains("<"), "read detail must not contain raw output markup")
            XCTAssertFalse(detail.contains("\n"), "read detail must be a single line")
            XCTAssertFalse(detail.contains("/"), "read shows just the filename, like the web client")
        }
    }

    func testEditShowsFilenameAndDiffBadge() {
        let (label, detail) = ToolDisplay.describe(firstTool("edit"))
        XCTAssertEqual(label, "Edit")
        // Real filediff in the fixture: additions 5, deletions 4.
        XCTAssertEqual(detail, "ast_query.h  +5 \u{2212}4")
    }

    func testShellShowsDescription() {
        let (label, detail) = ToolDisplay.describe(firstTool("bash"))
        XCTAssertEqual(label, "Shell")
        XCTAssertEqual(detail, "Configure cmake build")
    }

    func testGrepShowsPatternAndMatchCount() {
        let (label, detail) = ToolDisplay.describe(firstTool("grep"))
        XCTAssertEqual(label, "Grep")
        // Real grep in the fixture found nothing (metadata.matches == 0).
        XCTAssertEqual(detail, "BindParameter|bind.*param|:refId|:result  no matches")
    }

    func testTodoShowsCompletedRatio() {
        let (label, detail) = ToolDisplay.describe(firstTool("todowrite"))
        XCTAssertEqual(label, "To-dos")
        // 5 todos, none completed (4 pending + 1 in_progress).
        XCTAssertEqual(detail, "0/5")
    }

    func testQuestionLabel() {
        let (label, _) = ToolDisplay.describe(firstTool("question"))
        XCTAssertEqual(label, "Questions")
    }

    // MARK: patch + file parts

    func testPatchSummary() {
        let patch: PatchContent = firstPart { if case .patch(let p) = $0 { return p }; return nil }
        XCTAssertEqual(PatchDisplay.summary(patch), "1 file")
    }

    func testFileChipShowsNameAndLine() {
        let file: FileRefContent = firstPart { if case .file(let f) = $0 { return f }; return nil }
        // Real IDE context attachment: codegen_tests_create_table.cpp at line 266.
        XCTAssertEqual(FileRefDisplay.chip(file), "codegen_tests_create_table.cpp:266")
    }

    // MARK: pure-logic edge cases

    func testDiffBadgeNilWhenNoChanges() {
        XCTAssertNil(ToolDisplay.diffBadge(nil))
        XCTAssertNil(ToolDisplay.diffBadge(FileDiff(additions: 0, deletions: 0)))
        XCTAssertEqual(ToolDisplay.diffBadge(FileDiff(additions: 3, deletions: 0)), "+3 \u{2212}0")
    }

    func testLineRangeSpanAndSingle() {
        XCTAssertEqual(FileRefDisplay.lineRange("file:///a.cpp?start=10&end=10"), "10")
        XCTAssertEqual(FileRefDisplay.lineRange("file:///a.cpp?start=10&end=20"), "10-20")
        XCTAssertNil(FileRefDisplay.lineRange("file:///a.cpp"))
    }

    func testTodoRatio() {
        XCTAssertEqual(ToolDisplay.todoRatio([MetaTodo(status: "completed"),
                                              MetaTodo(status: "pending")]), "1/2")
        XCTAssertNil(ToolDisplay.todoRatio(nil))
        XCTAssertNil(ToolDisplay.todoRatio([]))
    }
}
