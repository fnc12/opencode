import UIKit

/// A fenced code block: syntax-highlighted monospace text in a rounded box that
/// scrolls horizontally so long lines aren't wrapped (like the web client).
final class CodeBlockView: UIView {
    static let font = UIFont.monospacedSystemFont(ofSize: 12.5, weight: .regular)
    nonisolated static let fontSize: CGFloat = 12.5
    nonisolated static let padH: CGFloat = 12
    nonisolated static let padV: CGFloat = 10

    private let scroll = UIScrollView()
    private let textView = UITextView()
    private let copyButton = UIButton(type: .system)
    private let code: NSAttributedString
    private var revertWork: DispatchWorkItem?

    /// `selectable` turns on native character selection + the copy menu (the
    /// message detail screen). In the transcript list it stays off, so a tap on a
    /// code row still opens the message detail instead of starting a selection.
    /// Either way a "copy the whole block" button sits in the top-right corner.
    init(code: NSAttributedString, selectable: Bool = false) {
        self.code = code
        super.init(frame: .zero)
        backgroundColor = .tertiarySystemFill
        layer.cornerRadius = 8
        layer.cornerCurve = .continuous
        clipsToBounds = true

        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceHorizontal = false

        textView.isEditable = false
        textView.isSelectable = selectable
        textView.isUserInteractionEnabled = selectable // off ⇒ taps pass to the cell
        textView.isScrollEnabled = false               // the outer scroll pans long lines
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainer.lineBreakMode = .byClipping // no wrapping — each line stays one line
        textView.dataDetectorTypes = []
        textView.attributedText = code

        scroll.addSubview(textView)
        addSubview(scroll)

        // "Copy the whole block" button — a UIControl, so its tap copies rather than
        // scrolling the code or (in the list) opening the message detail.
        setCopyIcon("doc.on.doc", tint: .secondaryLabel)
        copyButton.backgroundColor = UIColor.tertiarySystemBackground.withAlphaComponent(0.9)
        copyButton.layer.cornerRadius = 6
        copyButton.accessibilityIdentifier = "code.copy"
        copyButton.accessibilityLabel = "Copy code"
        copyButton.addTarget(self, action: #selector(copyTapped), for: .touchUpInside)
        addSubview(copyButton) // above the scroll, so it stays put as code pans
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func setCopyIcon(_ name: String, tint: UIColor) {
        let cfg = UIImage.SymbolConfiguration(pointSize: 12, weight: .semibold)
        copyButton.setImage(UIImage(systemName: name, withConfiguration: cfg), for: .normal)
        copyButton.tintColor = tint
    }

    @objc private func copyTapped() {
        UIPasteboard.general.string = code.string
        UISelectionFeedbackGenerator().selectionChanged()
        setCopyIcon("checkmark", tint: .systemGreen)
        revertWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.setCopyIcon("doc.on.doc", tint: .secondaryLabel) }
        revertWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: work)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        scroll.frame = bounds
        let big = CGFloat.greatestFiniteMagnitude
        let contentW = ceil(code.boundingRect(
            with: CGSize(width: big, height: big),
            options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).width)
        let w = max(contentW, bounds.width - Self.padH * 2)
        let h = bounds.height - Self.padV * 2
        textView.frame = CGRect(x: Self.padH, y: Self.padV, width: w, height: h)
        scroll.contentSize = CGSize(width: w + Self.padH * 2, height: bounds.height)
        let bw: CGFloat = 30, bh: CGFloat = 24
        copyButton.frame = CGRect(x: bounds.width - bw - 4, y: 4, width: bw, height: bh)
    }

    /// Fixed height from the line count (code doesn't wrap), plus vertical padding.
    nonisolated static func height(for code: NSAttributedString) -> CGFloat {
        let lineHeight = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular).lineHeight
        let lines = max(1, code.string.components(separatedBy: "\n").count)
        return ceil(CGFloat(lines) * lineHeight) + padV * 2
    }
}
