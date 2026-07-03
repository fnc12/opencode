import SwiftUI
import UIKit

/// A read-only, self-sizing text view that supports proper character-level
/// selection + the copy menu (SwiftUI `Text` selection is all-or-nothing and
/// flaky inside scroll views). Used on the message detail screen.
struct SelectableText: UIViewRepresentable {
    let text: String
    var monospaced = false
    var color: UIColor = .label

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.dataDetectorTypes = []
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        view.setContentHuggingPriority(.required, for: .vertical)
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        view.text = text
        view.font = monospaced
            ? .monospacedSystemFont(ofSize: 13, weight: .regular)
            : .preferredFont(forTextStyle: .callout)
        view.textColor = color
    }

    /// Wrap + self-size to the width SwiftUI proposes (otherwise the text view
    /// lays out at its full intrinsic width and gets clipped).
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? UIScreen.main.bounds.width
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: fit.height)
    }
}
