import UIKit

/// One renderable unit of a message body. Splitting a message into bounded
/// blocks keeps any single text layer small (fixing scroll jank) and lets a
/// GitHub-flavored table render as a real, horizontally-scrollable grid instead
/// of raw `| a | b |` text.
enum MessageBlock {
    case text(NSAttributedString)
    case table(MessageTable)
    /// A fenced code block (```lang … ```), already syntax-highlighted.
    case code(NSAttributedString)
    /// An image attachment (e.g. a pasted screenshot), decoded from the file
    /// part's `data:` URL — shown inline and tappable for a full-screen view.
    case image(UIImage)
}

/// A parsed markdown table: a header row plus data rows of raw cell strings.
struct MessageTable {
    let header: [String]
    let rows: [[String]]
    var columnCount: Int { max(header.count, rows.map(\.count).max() ?? 0) }
}

/// Detects and parses GitHub-flavored markdown tables.
enum MarkdownTable {
    /// A separator row like `|---|:--:|` (dashes/colons/pipes/spaces only).
    static func isSeparator(_ line: String?) -> Bool {
        guard let line else { return false }
        let t = line.trimmingCharacters(in: .whitespaces)
        guard t.contains("-"), t.contains("|") else { return false }
        return t.allSatisfy { "|-: ".contains($0) }
    }

    /// A line is a table start when it holds a pipe and the next line is a separator.
    static func isStart(_ line: String, next: String?) -> Bool {
        line.contains("|") && isSeparator(next)
    }

    static func cells(_ line: String) -> [String] {
        var s = line.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("|") { s.removeFirst() }
        if s.hasSuffix("|") { s.removeLast() }
        return s.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// Parses a run of table lines (header, separator, data rows). Returns nil if
    /// there's no header row.
    static func parse(_ lines: [String]) -> MessageTable? {
        let dataRows = lines.filter { !isSeparator($0) }.map(cells)
        guard let header = dataRows.first else { return nil }
        return MessageTable(header: header, rows: Array(dataRows.dropFirst()))
    }
}
