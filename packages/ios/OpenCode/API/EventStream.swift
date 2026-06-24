import Foundation

/// Minimal Server-Sent Events reader over URLSession's async byte stream.
///
/// Yields the raw JSON payload of each `data:` frame as `Data` (which is
/// `Sendable`); the caller decodes it into a `ServerEvent` on its own actor.
/// Cancelling the consuming task tears down the underlying URLSession request.
struct EventStream {
    let url: URL
    let authHeader: String?

    func frames() -> AsyncThrowingStream<Data, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var request = URLRequest(url: url)
                    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    // Long-lived stream: keep the request from timing out while idle.
                    request.timeoutInterval = 86_400
                    if let authHeader {
                        request.setValue(authHeader, forHTTPHeaderField: "Authorization")
                    }

                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    guard let http = response as? HTTPURLResponse,
                          (200...299).contains(http.statusCode) else {
                        throw ClientError.http((response as? HTTPURLResponse)?.statusCode ?? 0)
                    }

                    // Parse the SSE stream byte-by-byte rather than via
                    // `bytes.lines`: that async sequence withholds the blank
                    // line that terminates a frame until more input arrives, so
                    // events would only surface one event late (or never, while
                    // the model is idle). Splitting on `\n` ourselves dispatches
                    // each frame the moment its terminating blank line lands.
                    var line = Data()         // bytes of the current line (no \n)
                    var frame = Data()         // accumulated `data:` payload(s)

                    func flushLine() {
                        defer { line.removeAll(keepingCapacity: true) }
                        // Tolerate CRLF.
                        if line.last == 0x0D { line.removeLast() }
                        if line.isEmpty {
                            // Blank line terminates an event.
                            if !frame.isEmpty {
                                continuation.yield(frame)
                                frame.removeAll(keepingCapacity: true)
                            }
                            return
                        }
                        guard line.starts(with: Data("data:".utf8)) else { return } // ignore :, event:, id:, retry:
                        var payload = line.dropFirst("data:".count)
                        while payload.first == 0x20 { payload = payload.dropFirst() } // trim leading spaces
                        if !frame.isEmpty { frame.append(0x0A) } // join multi-line data with \n
                        frame.append(contentsOf: payload)
                    }

                    for try await byte in bytes {
                        try Task.checkCancellation()
                        if byte == 0x0A { // \n
                            flushLine()
                        } else {
                            line.append(byte)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
