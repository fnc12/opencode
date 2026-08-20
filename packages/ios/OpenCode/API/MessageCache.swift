import Foundation

/// A tiny on-disk cache of a session's newest message page, so reopening a
/// session paints instantly from disk while the network refresh runs in
/// parallel. Stores the raw server JSON (the `[MessageWithParts]` array) in the
/// caches directory, keyed by session id — best-effort and evictable (it's the
/// Caches dir, which the OS may purge), never fatal on read or write failure.
enum MessageCache {
    private static var directory: URL? {
        guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("message-cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func fileURL(_ sessionID: String) -> URL? {
        // Session ids are `ses_<hex>` (filename-safe), but sanitize defensively.
        let safe = sessionID.replacingOccurrences(of: "/", with: "_")
        return directory?.appendingPathComponent("\(safe).json")
    }

    /// The last-cached newest page for a session, or nil on a miss / decode error.
    static func load(_ sessionID: String) -> [MessageWithParts]? {
        guard let url = fileURL(sessionID), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode([MessageWithParts].self, from: data)
    }

    /// Persist the raw newest-page response body (the server's JSON array), which
    /// `load` decodes back with the production model path. Atomic so a crash
    /// mid-write can't leave a half-file that fails to decode.
    static func save(_ sessionID: String, raw: Data) {
        guard let url = fileURL(sessionID) else { return }
        try? raw.write(to: url, options: .atomic)
    }
}
