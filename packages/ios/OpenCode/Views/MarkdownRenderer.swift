import UIKit

/// Converts Markdown text into an `NSAttributedString` with concrete fonts and
/// colors. Unlike SwiftUI's `Text`, UIKit does not interpret Markdown
/// presentation intents, so we resolve them to real fonts ourselves: bold,
/// italic, inline `code` (monospaced), links, and ATX headers (`#`…`######`).
/// Block constructs beyond headers (fenced code, blockquotes) render as plain
/// inline text for now.
enum MarkdownRenderer {
    static func attributed(_ markdown: String, font: UIFont, color: UIColor) -> NSAttributedString {
        let result = NSMutableAttributedString()
        let lines = markdown.components(separatedBy: "\n")
        for (index, line) in lines.enumerated() {
            if index > 0 { result.append(NSAttributedString(string: "\n")) }
            if let (level, content) = header(line) {
                result.append(inline(content, font: headerFont(level, base: font), color: color))
            } else {
                result.append(inline(line, font: font, color: color))
            }
        }
        return result
    }

    // MARK: headers

    private static func header(_ line: String) -> (level: Int, content: String)? {
        let trimmed = line.drop(while: { $0 == " " })
        var level = 0
        var rest = Substring(trimmed)
        while rest.first == "#" { level += 1; rest = rest.dropFirst() }
        guard (1...6).contains(level), rest.first == " " else { return nil }
        return (level, String(rest.drop(while: { $0 == " " })))
    }

    private static func headerFont(_ level: Int, base: UIFont) -> UIFont {
        let size: CGFloat
        switch level {
        case 1: size = base.pointSize + 7
        case 2: size = base.pointSize + 4
        case 3: size = base.pointSize + 2
        default: size = base.pointSize + 1
        }
        return UIFont.boldSystemFont(ofSize: size)
    }

    // MARK: inline

    /// Renders a single line of inline markdown (bold / italic / `code` / links)
    /// — used for table cells, which aren't block constructs.
    static func inlineAttributed(_ string: String, font: UIFont, color: UIColor) -> NSAttributedString {
        inline(string, font: font, color: color)
    }

    private static func inline(_ string: String, font: UIFont, color: UIColor) -> NSAttributedString {
        guard !string.isEmpty else { return NSAttributedString(string: "") }
        let options = AttributedString.MarkdownParsingOptions(
            allowsExtendedAttributes: true,
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible)
        guard let parsed = try? AttributedString(markdown: string, options: options) else {
            return NSAttributedString(string: string, attributes: [.font: font, .foregroundColor: color])
        }

        let output = NSMutableAttributedString()
        for run in parsed.runs {
            let text = String(parsed[run.range].characters)
            var resolved = font
            var foreground = color
            var background: UIColor?

            if let intent = run.inlinePresentationIntent {
                if intent.contains(.stronglyEmphasized) { resolved = withTraits(resolved, .traitBold) }
                if intent.contains(.emphasized) { resolved = withTraits(resolved, .traitItalic) }
                if intent.contains(.code) {
                    resolved = .monospacedSystemFont(ofSize: font.pointSize - 1, weight: .regular)
                    background = UIColor.secondaryLabel.withAlphaComponent(0.18)
                }
            }

            var attributes: [NSAttributedString.Key: Any] = [.font: resolved, .foregroundColor: foreground]
            if let background { attributes[.backgroundColor] = background }
            if let link = run.link {
                attributes[.link] = link
                attributes[.foregroundColor] = UIColor.systemBlue
                foreground = .systemBlue
            }
            output.append(NSAttributedString(string: text, attributes: attributes))
        }
        return output
    }

    private static func withTraits(_ font: UIFont, _ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let combined = font.fontDescriptor.symbolicTraits.union(traits)
        guard let descriptor = font.fontDescriptor.withSymbolicTraits(combined) else { return font }
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }
}
