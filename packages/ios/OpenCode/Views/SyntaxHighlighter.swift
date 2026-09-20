import UIKit
import Highlightr

/// Syntax-highlights code into an `NSAttributedString` via highlight.js
/// (Highlightr), so `read` results render like the web client. Engines are built
/// once per theme and reused — highlighting a file snippet is a few milliseconds.
/// The result drops into `SelectableText`, so character selection is preserved.
@MainActor
enum SyntaxHighlighter {
    private static let dark: Highlightr? = make("atom-one-dark")
    private static let light: Highlightr? = make("atom-one-light")

    private static func make(_ theme: String) -> Highlightr? {
        let h = Highlightr()
        h?.setTheme(to: theme)
        return h
    }

    /// Highlight `code` for the language inferred from `filename`'s extension,
    /// picking a theme that reads well on the current light/dark background.
    static func attributed(_ code: String, filename: String?, dark isDark: Bool, fontSize: CGFloat = 12) -> NSAttributedString? {
        guard let engine = isDark ? dark : light else { return nil }
        engine.theme.codeFont = .monospacedSystemFont(ofSize: fontSize, weight: .regular)
        return engine.highlight(code, as: language(for: filename), fastRender: true)
    }

    /// Highlight `code` for an explicit language name (a fenced code block's
    /// ```lang info string). nil/unknown falls back to highlight.js auto-detect.
    static func attributed(_ code: String, language name: String?, dark isDark: Bool, fontSize: CGFloat = 12) -> NSAttributedString? {
        guard let engine = isDark ? dark : light else { return nil }
        engine.theme.codeFont = .monospacedSystemFont(ofSize: fontSize, weight: .regular)
        let lang = name.flatMap { $0.isEmpty ? nil : normalizeLanguage($0) }
        return engine.highlight(code, as: lang, fastRender: true)
    }

    /// Map common fence info strings / aliases to highlight.js language ids.
    private static func normalizeLanguage(_ lang: String) -> String {
        switch lang.lowercased() {
        case "c++", "cpp", "cc", "h", "hpp": return "cpp"
        case "objc", "objective-c": return "objectivec"
        case "js", "node", "jsx": return "javascript"
        case "ts", "tsx": return "typescript"
        case "py", "python3": return "python"
        case "sh", "shell", "zsh", "console": return "bash"
        case "yml": return "yaml"
        case "html", "htm": return "xml"
        case "cs": return "csharp"
        case "rb": return "ruby"
        case "rs": return "rust"
        case "kt": return "kotlin"
        case "toml": return "ini"
        default: return lang.lowercased()
        }
    }

    /// A `read` file result: strip the "123: " line-number gutter, highlight the
    /// code, then re-attach the numbers dimmed — mirroring the web's gutter.
    static func readContent(_ content: String, filename: String?, dark isDark: Bool, fontSize: CGFloat = 12) -> NSAttributedString {
        let mono = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        var gutters: [String] = []
        var code: [String] = []
        for line in content.components(separatedBy: "\n") {
            if let r = line.range(of: #"^\s*\d+:\s?"#, options: .regularExpression) {
                gutters.append(String(line[..<r.upperBound]))
                code.append(String(line[r.upperBound...]))
            } else {
                gutters.append("")
                code.append(line)
            }
        }
        let highlighted = attributed(code.joined(separator: "\n"), filename: filename, dark: isDark, fontSize: fontSize)
            ?? NSAttributedString(string: code.joined(separator: "\n"),
                                  attributes: [.font: mono, .foregroundColor: UIColor.label])

        // Re-attach the gutter per line without disturbing the highlighted ranges.
        let out = NSMutableAttributedString()
        let codeLines = splitKeepingRanges(highlighted)
        for (i, codeLine) in codeLines.enumerated() {
            if i > 0 { out.append(NSAttributedString(string: "\n")) }
            if i < gutters.count, !gutters[i].isEmpty {
                out.append(NSAttributedString(string: gutters[i],
                                              attributes: [.font: mono, .foregroundColor: UIColor.tertiaryLabel]))
            }
            out.append(codeLine)
        }
        return out
    }

    /// Split an attributed string on "\n" boundaries, preserving each line's attrs.
    private static func splitKeepingRanges(_ s: NSAttributedString) -> [NSAttributedString] {
        var lines: [NSAttributedString] = []
        let ns = s.string as NSString
        var start = 0
        while start <= ns.length {
            let nl = ns.range(of: "\n", options: [], range: NSRange(location: start, length: ns.length - start))
            let end = nl.location == NSNotFound ? ns.length : nl.location
            lines.append(s.attributedSubstring(from: NSRange(location: start, length: end - start)))
            if nl.location == NSNotFound { break }
            start = nl.location + 1
        }
        return lines
    }

    /// Map a filename to a highlight.js language id (falls back to the raw
    /// extension, which highlight.js recognises for many languages).
    static func language(for filename: String?) -> String? {
        guard let ext = (filename as NSString?)?.pathExtension.lowercased(), !ext.isEmpty else { return nil }
        switch ext {
        case "cpp", "cc", "cxx", "hpp", "hxx", "h", "c", "ino": return "cpp"
        case "swift": return "swift"
        case "m", "mm": return "objectivec"
        case "js", "mjs", "cjs", "jsx": return "javascript"
        case "ts", "tsx": return "typescript"
        case "py", "pyi": return "python"
        case "go": return "go"
        case "rs": return "rust"
        case "java": return "java"
        case "kt", "kts": return "kotlin"
        case "rb": return "ruby"
        case "php": return "php"
        case "cs": return "csharp"
        case "sh", "bash", "zsh": return "bash"
        case "json": return "json"
        case "yml", "yaml": return "yaml"
        case "toml": return "ini"
        case "md", "markdown": return "markdown"
        case "html", "htm", "xml": return "xml"
        case "css", "scss", "less": return "css"
        case "sql": return "sql"
        default: return ext
        }
    }
}
