import XCTest
@testable import OpenCode

/// The last few computed accessors on small model types — SessionStatus busy/idle,
/// SessionFileDiff.id, ProviderInfo/ModelInfo display names, QuestionItem flags.
final class SmallModelAccessorTests: XCTestCase {
    private func decode<T: Decodable>(_ json: String) -> T {
        try! JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    func testSessionStatusBusyIdle() {
        let busy: SessionStatus = decode(#"{"type":"busy","attempt":1}"#)
        XCTAssertTrue(busy.isBusy)
        XCTAssertFalse(busy.isIdle)
        let idle: SessionStatus = decode(#"{"type":"idle"}"#)
        XCTAssertTrue(idle.isIdle)
        XCTAssertFalse(idle.isBusy)
    }

    func testSessionFileDiffIdPrefersFile() {
        let named: SessionFileDiff = decode(#"{"file":"src/a.swift","patch":"@@","additions":2,"deletions":1,"status":"modified"}"#)
        XCTAssertEqual(named.id, "src/a.swift")
        // No file → a non-empty synthesized id (UUID), so ForEach stays stable.
        let anon: SessionFileDiff = decode(#"{"additions":0,"deletions":0}"#)
        XCTAssertFalse(anon.id.isEmpty)
    }

    func testProviderAndModelDisplayNames() {
        let provider: ProviderInfo = decode(#"{"id":"openai","name":"OpenAI","models":{"gpt":{"id":"gpt","name":"GPT-5"}}}"#)
        XCTAssertEqual(provider.name, "OpenAI")
        XCTAssertEqual(provider.models["gpt"]?.displayName, "GPT-5")
        // A model without a name falls back to its id.
        let noName: ModelInfo = decode(#"{"id":"glm-5.2"}"#)
        XCTAssertEqual(noName.displayName, "glm-5.2")
    }

    func testQuestionItemFlagsAndId() {
        let multi: QuestionItem = decode(#"{"question":"Pick","header":"H","options":[{"label":"A","description":"a"}],"multiple":true}"#)
        XCTAssertTrue(multi.allowsMultiple)
        XCTAssertEqual(multi.id, "H|Pick")
        let single: QuestionItem = decode(#"{"question":"Q","header":"H2","options":[]}"#)
        XCTAssertFalse(single.allowsMultiple)
    }
}
