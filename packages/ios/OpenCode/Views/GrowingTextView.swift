import SwiftUI
import UIKit

/// An editable, auto-growing multi-line text field backed by UITextView.
/// SwiftUI's `TextField(axis: .vertical)` renders its text clipped above the
/// field when hosted in an `inputAccessoryView` (the composer), so the composer
/// uses this instead. Grows up to `maxLines`, then scrolls.
struct GrowingTextView: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String = ""
    var maxLines: Int = 5

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator
        view.font = .preferredFont(forTextStyle: .body)
        view.backgroundColor = .clear
        view.isScrollEnabled = false
        view.textContainerInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
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

    func updateUIView(_ view: UITextView, context: Context) {
        if view.text != text { view.text = text }
        context.coordinator.placeholderLabel?.isHidden = !text.isEmpty
        // Scroll instead of growing once past maxLines.
        let line = view.font?.lineHeight ?? 20
        let cap = line * CGFloat(maxLines) + view.textContainerInset.top + view.textContainerInset.bottom
        let fit = view.sizeThatFits(CGSize(width: view.bounds.width, height: .greatestFiniteMagnitude)).height
        view.isScrollEnabled = fit > cap
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? UIScreen.main.bounds.width
        let line = uiView.font?.lineHeight ?? 20
        let cap = line * CGFloat(maxLines) + uiView.textContainerInset.top + uiView.textContainerInset.bottom
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        return CGSize(width: width, height: min(max(fit, line + 16), cap))
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        let parent: GrowingTextView
        weak var placeholderLabel: UILabel?
        init(_ parent: GrowingTextView) { self.parent = parent }
        func textViewDidChange(_ view: UITextView) {
            parent.text = view.text
            placeholderLabel?.isHidden = !view.text.isEmpty
        }
    }
}
