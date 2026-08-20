import SwiftUI
import UIKit

/// An editable, auto-growing multi-line text field backed by UITextView.
/// SwiftUI's `TextField(axis: .vertical)` renders its text clipped above the
/// field when hosted in an `inputAccessoryView` (the composer), so the composer
/// uses this instead. Grows up to `maxLines`, then scrolls.
///
/// Sizing is driven entirely by the UITextView's own `intrinsicContentSize`,
/// measured against its *real* laid-out width (from Auto Layout inside the
/// accessory), rather than SwiftUI's `sizeThatFits` proposal. The proposal's
/// width is `nil` during the accessory's intrinsic-sizing pass, and the old code
/// fell back to the full screen width — so a line that actually wraps was
/// measured as fitting on one line, and the field stayed one line tall while the
/// text drew a second. That left the text vertically off-centre ("asymmetric")
/// until some later relayout (keyboard toggle, backgrounding) re-measured with
/// the correct width. Measuring from `bounds.width` and invalidating on every
/// width/text change makes the height correct on the same pass the line appears.
struct GrowingTextView: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String = ""
    var maxLines: Int = 5

    func makeUIView(context: Context) -> HeightTrackingTextView {
        let view = HeightTrackingTextView()
        view.maxLines = maxLines
        view.delegate = context.coordinator
        view.font = .preferredFont(forTextStyle: .body)
        view.backgroundColor = .clear
        view.isScrollEnabled = false
        view.textContainerInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        // Fill the width the composer HStack offers; drive height from content.
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.required, for: .vertical)

        let placeholderLabel = UILabel()
        placeholderLabel.text = placeholder
        placeholderLabel.font = view.font
        placeholderLabel.textColor = .placeholderText
        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(placeholderLabel)
        NSLayoutConstraint.activate([
            placeholderLabel.topAnchor.constraint(equalTo: view.topAnchor, constant: 8),
            placeholderLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
        ])
        context.coordinator.placeholderLabel = placeholderLabel
        return view
    }

    func updateUIView(_ view: HeightTrackingTextView, context: Context) {
        view.maxLines = maxLines
        if view.text != text {
            view.text = text
            view.invalidateIntrinsicContentSize() // programmatic set (e.g. cleared on send)
        }
        context.coordinator.placeholderLabel?.isHidden = !text.isEmpty
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        let parent: GrowingTextView
        weak var placeholderLabel: UILabel?
        init(_ parent: GrowingTextView) { self.parent = parent }
        func textViewDidChange(_ view: UITextView) {
            parent.text = view.text
            placeholderLabel?.isHidden = !view.text.isEmpty
            view.invalidateIntrinsicContentSize()
        }
    }
}

/// A UITextView that reports the height its text needs (up to `maxLines`, then
/// scrolls) as its `intrinsicContentSize`, measured against its current width.
/// Re-measures whenever the width changes, so the height is always correct for
/// the width it's actually drawn at — no dependency on SwiftUI's size proposal.
final class HeightTrackingTextView: UITextView {
    var maxLines: Int = 5
    private var lastMeasuredWidth: CGFloat = -1

    /// The single-line height: one line plus the top+bottom text insets. Also the
    /// floor for the field so an empty field matches a one-line field exactly.
    private var minHeight: CGFloat {
        (font?.lineHeight ?? 20) + textContainerInset.top + textContainerInset.bottom
    }
    private var capHeight: CGFloat {
        (font?.lineHeight ?? 20) * CGFloat(maxLines) + textContainerInset.top + textContainerInset.bottom
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // The width Auto Layout gives us drives the wrap point; when it changes,
        // the needed height can change too, so re-measure.
        if bounds.width != lastMeasuredWidth {
            lastMeasuredWidth = bounds.width
            invalidateIntrinsicContentSize()
        }
    }

    override var intrinsicContentSize: CGSize {
        // Before the first layout we have no real width; report the one-line
        // height so the field starts correctly sized.
        guard bounds.width > 0 else {
            return CGSize(width: UIView.noIntrinsicMetric, height: minHeight)
        }
        let fit = sizeThatFits(CGSize(width: bounds.width, height: .greatestFiniteMagnitude)).height
        // Scroll (don't grow) once the content exceeds the cap. Setting this here
        // is safe: it doesn't change intrinsic height and only flips at the cap.
        let scroll = fit > capHeight + 0.5
        if isScrollEnabled != scroll { isScrollEnabled = scroll }
        let height = min(max(fit, minHeight), capHeight)
        return CGSize(width: UIView.noIntrinsicMetric, height: height)
    }
}
