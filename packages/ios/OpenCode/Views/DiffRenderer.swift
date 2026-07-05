import UIKit

/// Renders a unified diff into a colored, monospaced `NSAttributedString`:
/// additions green, deletions red, hunk headers dimmed — mirroring the web
/// client's ContentDiff. Line backgrounds tint the whole row so the change is
/// scannable, not just the leading +/-.
enum DiffRenderer {
    static func attributed(_ diff: String, fontSize: CGFloat = 12) -> NSAttributedString {
        let font = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        let add = UIColor.systemGreen
        let del = UIColor.systemRed
        let addBG = UIColor.systemGreen.withAlphaComponent(0.12)
        let delBG = UIColor.systemRed.withAlphaComponent(0.12)
        let hunk = UIColor.systemBlue

        let out = NSMutableAttributedString()
        let lines = diff.components(separatedBy: "\n")
        for (i, line) in lines.enumerated() {
            if i > 0 { out.append(NSAttributedString(string: "\n")) }
            var fg = UIColor.label
            var bg: UIColor?
            if line.hasPrefix("+++") || line.hasPrefix("---") {
                fg = .secondaryLabel
            } else if line.hasPrefix("@@") {
                fg = hunk
            } else if line.hasPrefix("+") {
                fg = add; bg = addBG
            } else if line.hasPrefix("-") {
                fg = del; bg = delBG
            } else {
                fg = .secondaryLabel
            }
            var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: fg]
            if let bg { attrs[.backgroundColor] = bg }
            out.append(NSAttributedString(string: line, attributes: attrs))
        }
        return out
    }
}
