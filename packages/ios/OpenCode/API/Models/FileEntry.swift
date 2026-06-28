import Foundation

/// An entry from `GET /file` (directory listing) — used by the folder browser.
struct FileEntry: Decodable, Identifiable {
    let name: String
    let absolute: String
    let type: String          // "directory" | "file"
    let ignored: Bool?

    var id: String { absolute }
    var isDirectory: Bool { type == "directory" }
}
