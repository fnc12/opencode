import UIKit

/// A fenced code block: syntax-highlighted monospace text in a rounded box that
/// scrolls horizontally so long lines aren't wrapped (like the web client).
final class CodeBlockView: UIView {
    static let font = UIFont.monospacedSystemFont(ofSize: 12.5, weight: .regular)
    nonisolated static let fontSize: CGFloat = 12.5
    nonisolated static let padH: CGFloat = 12
    nonisolated static let padV: CGFloat = 10

    private let scroll = UIScrollView()
    private let label = UILabel()

    init(code: NSAttributedString) {
        super.init(frame: .zero)
        backgroundColor = .tertiarySystemFill
        layer.cornerRadius = 8
        layer.cornerCurve = .continuous
        clipsToBounds = true

        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceHorizontal = false

        label.numberOfLines = 0
        label.lineBreakMode = .byClipping // no wrapping — each source line stays one line
        label.attributedText = code

        scroll.addSubview(label)
        addSubview(scroll)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        scroll.frame = bounds
        let big = CGFloat.greatestFiniteMagnitude
        let contentW = ceil(label.attributedText?.boundingRect(
            with: CGSize(width: big, height: big),
            options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).width ?? 0)
        let w = max(contentW, bounds.width - Self.padH * 2)
        let h = bounds.height - Self.padV * 2
        label.frame = CGRect(x: Self.padH, y: Self.padV, width: w, height: h)
        scroll.contentSize = CGSize(width: w + Self.padH * 2, height: bounds.height)
    }

    /// Fixed height from the line count (code doesn't wrap), plus vertical padding.
    nonisolated static func height(for code: NSAttributedString) -> CGFloat {
        let lineHeight = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular).lineHeight
        let lines = max(1, code.string.components(separatedBy: "\n").count)
        return ceil(CGFloat(lines) * lineHeight) + padV * 2
    }
}
