import XCTest
@testable import OpenCode

/// Exercises the whole `MessageRenderer.render` block-building
/// pipeline (textToBlocks, toolLine, patchLine, fileChip, reasoningBlocks, code
/// fences, GFM tables) directly, without the UITableView — the bulk of
/// MessageListView's logic that was near-0%.
@MainActor
final class MessageRenderPipelineTests: XCTestCase {
    private func decode(_ json: String) -> MessageWithParts {
        try! JSONDecoder().decode(MessageWithParts.self, from: Data(json.utf8))
    }

    private func assistant(parts: String) -> MessageWithParts {
        decode(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[\#(parts)]}
        """#)
    }

    private func render(_ m: MessageWithParts) -> RenderedMessage {
        MessageRenderer.render(m)
    }

    func testUserRoleAndColor() {
        let m = decode(#"{"info":{"id":"m","sessionID":"s","role":"user","time":{"created":1}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"hi"}]}"#)
        let r = render(m)
        XCTAssertEqual(r.roleText, "You")
    }

    func testTextBlock() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"Hello world"}"#))
        XCTAssertTrue(r.plainText.contains("Hello world"))
    }

    func testCodeFenceBecomesCodeBlock() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"```swift\nlet x = 1\n```"}"#))
        let hasCode = r.blocks.contains { if case .code = $0 { return true }; return false }
        XCTAssertTrue(hasCode, "a fenced block must render as a .code block")
    }

    func testGFMTableBecomesTableBlock() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"| A | B |\n|---|---|\n| 1 | 2 |"}"#))
        let hasTable = r.blocks.contains { if case .table = $0 { return true }; return false }
        XCTAssertTrue(hasTable)
    }

    func testToolLineRendered() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed"}}"#))
        XCTAssertFalse(r.blocks.isEmpty)
    }

    func testReasoningCollapsesToThinking() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"reasoning","text":"deep thoughts"}"#))
        XCTAssertTrue(r.plainText.contains("Thinking"))
    }

    func testFileChipRendered() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"file","filename":"main.swift","url":"file:///main.swift","mime":"text/x-swift"}"#))
        XCTAssertTrue(r.plainText.contains("main.swift") || r.blocks.contains { if case .text = $0 { return true }; return false })
    }

    func testImageFilePartBecomesImageBlock() {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2))
        let b64 = renderer.image { _ in }.pngData()!.base64EncodedString()
        let r = render(assistant(parts: "{\"id\":\"p\",\"sessionID\":\"s\",\"messageID\":\"m\",\"type\":\"file\",\"filename\":\"shot.png\",\"mime\":\"image/png\",\"url\":\"data:image/png;base64,\(b64)\"}"))
        let hasImage = r.blocks.contains { if case .image = $0 { return true }; return false }
        XCTAssertTrue(hasImage, "an image file part must render as an .image block")
    }

    func testErrorAppendedForAssistantError() {
        let m = decode(#"""
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"error":{"name":"ProviderError","data":{"message":"rate limited"}}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"partial"}]}
        """#)
        let r = render(m)
        XCTAssertTrue(r.plainText.contains("Error"), "an assistant error must be surfaced")
    }

    func testCompactionMarker() {
        let auto = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"compaction","auto":true}"#))
        XCTAssertTrue(auto.plainText.contains("earlier context summarized"))
        let manual = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"compaction","auto":false}"#))
        XCTAssertTrue(manual.plainText.contains("context summarized"))
    }

    func testStepStartTitle() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"step-start","title":"Planning"}"#))
        XCTAssertTrue(r.plainText.contains("Planning"))
    }

    func testPatchRow() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"patch","hash":"abc","files":["a.txt","b.txt"]}"#))
        XCTAssertTrue(r.plainText.contains("Patch"))
    }

    func testToolErrorSurfacesSnippet() {
        let r = render(assistant(parts: #"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"error","error":"boom failed"}}"#))
        XCTAssertTrue(r.plainText.contains("boom failed"), "a tool error snippet must show")
    }

    func testRunningAndPendingToolMarks() {
        for status in ["running", "pending"] {
            let r = render(assistant(parts: "{\"id\":\"p\",\"sessionID\":\"s\",\"messageID\":\"m\",\"type\":\"tool\",\"callID\":\"c\",\"tool\":\"bash\",\"state\":{\"status\":\"\(status)\"}}"))
            XCTAssertFalse(r.blocks.isEmpty)
        }
    }

    func testLongTextIsChunked() {
        // >1500 chars over many lines → boundedChunks splits into multiple blocks.
        let long = Array(repeating: "line of some length here", count: 120).joined(separator: "\\n")
        let r = render(assistant(parts: "{\"id\":\"p\",\"sessionID\":\"s\",\"messageID\":\"m\",\"type\":\"text\",\"text\":\"\(long)\"}"))
        XCTAssertGreaterThanOrEqual(r.blocks.count, 2, "a very long paragraph splits into bounded chunks")
    }

    func testTokenSummaryFormatsThousands() {
        let m = decode(#"{"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":2500,"output":1200,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"hi"}]}"#)
        XCTAssertEqual(render(m).metaText, "2k→1k")
    }

    func testSignatureChangesWithContent() {
        let a = decode(#"{"info":{"id":"m","sessionID":"s","role":"user","time":{"created":1}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"short"}]}"#)
        let b = decode(#"{"info":{"id":"m","sessionID":"s","role":"user","time":{"created":1}},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"a much longer body"}]}"#)
        XCTAssertNotEqual(MessageRenderer.signature(a), MessageRenderer.signature(b))
    }

    func testCodeBlockHighlightsThenFallsBack() {
        // A known language highlights; an unknown/empty language falls back to
        // plain monospaced text (still carrying the source).
        let swift = MessageRenderer.codeBlock("let x = 1", lang: "swift")
        XCTAssertEqual(swift.string, "let x = 1")
        let unknown = MessageRenderer.codeBlock("zzz yyy", lang: "not-a-language")
        XCTAssertEqual(unknown.string, "zzz yyy")
    }

    func testToolLineAppendsDetail() {
        // A bash tool with a command → the line carries the "✓ Shell  <cmd>" detail.
        let tool = toolContent(#"{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","input":{"command":"cargo test"}}}"#)
        let line = MessageRenderer.toolLine(tool).string
        XCTAssertTrue(line.contains("cargo test"), "the tool detail is appended: \(line)")
    }

    func testRenderHandlesStepFinishPart() {
        // A step-finish part is a no-op block (the `.stepFinish` break) but must not
        // crash the pipeline; the surrounding text still renders.
        let m = assistant(parts: #"""
        {"id":"p0","sessionID":"s","messageID":"m","type":"step-finish"},
        {"id":"p1","sessionID":"s","messageID":"m","type":"text","text":"After."}
        """#)
        let r = render(m)
        XCTAssertTrue(r.blocks.contains { if case .text = $0 { return true }; return false })
    }

    private func toolContent(_ json: String) -> ToolContent {
        let part = try! JSONDecoder().decode(MessagePart.self, from: Data(json.utf8))
        guard case .tool(let t)? = part.content else { fatalError("expected tool") }
        return t
    }

    /// The assistant branch of `signature` folds completion time, output tokens,
    /// and error text — so a still-generating message and its finished form (or an
    /// errored form) hash differently, letting the cell know to redraw.
    func testAssistantSignatureFoldsCompletionTokensAndError() {
        func msg(_ info: String) -> MessageWithParts {
            decode(#"{"info":{\#(info)},"parts":[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"hi"}]}"#)
        }
        let base = #""id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}"#
        let generating = msg(base)
        let completed = msg(#""id":"m","sessionID":"s","role":"assistant","time":{"created":1,"completed":2},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}"#)
        let moreTokens = msg(#""id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":50,"reasoning":0,"cache":{"read":0,"write":0}}"#)
        let errored = msg(base + #","error":{"name":"E","data":{"message":"boom"}}"#)
        let s = MessageRenderer.signature(generating)
        XCTAssertNotEqual(s, MessageRenderer.signature(completed), "completion time changes the signature")
        XCTAssertNotEqual(s, MessageRenderer.signature(moreTokens), "output tokens change the signature")
        XCTAssertNotEqual(s, MessageRenderer.signature(errored), "an error changes the signature")
    }
}
