import XCTest
@testable import OpenCode

/// The SSE byte parser. Regression guard for the bug that only surfaced live:
/// an event must be dispatched the moment its terminating blank line arrives,
/// and CRLF / multi-line `data:` must be handled.
final class EventStreamTests: XCTestCase {
    override func setUp() {
        super.setUp()
        URLProtocol.registerClass(StubSSEProtocol.self)
    }
    override func tearDown() {
        URLProtocol.unregisterClass(StubSSEProtocol.self)
        super.tearDown()
    }

    private func frames(from raw: String, expected: Int) async throws -> [String] {
        StubSSEProtocol.body = Data(raw.utf8)
        let stream = EventStream(url: URL(string: "sse-test://host/global/event")!, authHeader: nil)
        var out: [String] = []
        for try await data in stream.frames() {
            out.append(String(decoding: data, as: UTF8.self))
            if out.count == expected { break }
        }
        return out
    }

    func testDispatchesEachFrameOnBlankLine() async throws {
        let body = "data: {\"a\":1}\n\ndata: {\"b\":2}\n\n"
        let result = try await frames(from: body, expected: 2)
        XCTAssertEqual(result, ["{\"a\":1}", "{\"b\":2}"])
    }

    func testToleratesCRLF() async throws {
        let body = "data: {\"a\":1}\r\n\r\n"
        let result = try await frames(from: body, expected: 1)
        XCTAssertEqual(result, ["{\"a\":1}"])
    }

    func testJoinsMultiLineData() async throws {
        let body = "data: line1\ndata: line2\n\n"
        let result = try await frames(from: body, expected: 1)
        XCTAssertEqual(result, ["line1\nline2"])
    }

    func testIgnoresCommentsAndOtherFields() async throws {
        let body = ": heartbeat\nevent: message\ndata: {\"ok\":true}\n\n"
        let result = try await frames(from: body, expected: 1)
        XCTAssertEqual(result, ["{\"ok\":true}"])
    }
}

/// Serves a canned SSE body for the `sse-test` scheme via `URLSession.shared`.
final class StubSSEProtocol: URLProtocol {
    nonisolated(unsafe) static var body = Data()

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.scheme == "sse-test"
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let response = HTTPURLResponse(
            url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
