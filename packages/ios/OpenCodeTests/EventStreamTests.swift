import XCTest
@testable import OpenCode

/// Drives EventStream.frames() against a stub-backed URLSession so the SSE reader
/// (auth/tunnel headers, status handling, frame parsing, error finish) runs
/// offline. Uses the same StubURLProtocol as the ServerConnection fake tests.
final class EventStreamTests: XCTestCase {
    override func setUp() { super.setUp(); StubURLProtocol.reset() }

    private func stream(authHeader: String? = nil, tunnelToken: String? = nil) -> EventStream {
        EventStream(url: URL(string: "http://stub.local/global/event")!,
                    authHeader: authHeader, tunnelToken: tunnelToken,
                    session: StubURLProtocol.session())
    }

    func testParsesOneSSEFrameAndSendsHeaders() async throws {
        // A single well-formed SSE event: `data:` line then a blank terminator.
        StubURLProtocol.enqueue(body: "data: {\"hello\":\"world\"}\n\n")
        var frames: [Data] = []
        for try await frame in stream(authHeader: "Basic abc", tunnelToken: "tok_1").frames() {
            frames.append(frame)
            break // one frame is enough
        }
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(String(data: frames[0], encoding: .utf8), "{\"hello\":\"world\"}")
        // The request carried the auth + tunnel headers.
        let request = StubURLProtocol.seen.last
        XCTAssertEqual(request?.value(forHTTPHeaderField: "Authorization"), "Basic abc")
        XCTAssertEqual(request?.value(forHTTPHeaderField: "X-Tunnel-Token"), "tok_1")
        XCTAssertEqual(request?.value(forHTTPHeaderField: "Accept"), "text/event-stream")
    }

    func testMultiLineDataFramesJoin() async throws {
        // Two `data:` lines before the blank line join with a newline.
        StubURLProtocol.enqueue(body: "data: line1\ndata: line2\n\n")
        var first: Data?
        for try await frame in stream().frames() { first = frame; break }
        XCTAssertEqual(String(data: first ?? Data(), encoding: .utf8), "line1\nline2")
    }

    func testNon200Throws() async {
        StubURLProtocol.enqueue(401, body: "nope")
        do {
            for try await _ in stream().frames() { XCTFail("a 401 stream must throw") }
        } catch {
            // expected — the error-finish path.
        }
    }
}
