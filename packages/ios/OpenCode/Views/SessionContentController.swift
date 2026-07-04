import UIKit
import SwiftUI
import os

/// Self-sizing container so a SwiftUI-hosted composer can serve as a view
/// controller's `inputAccessoryView` — docking at the bottom and riding the
/// keyboard (including interactive dismiss) natively, no manual tracking.
final class InputBarView: UIView {
    init(content: UIView) {
        super.init(frame: CGRect(x: 0, y: 0, width: 0, height: 44))
        autoresizingMask = .flexibleHeight
        content.translatesAutoresizingMaskIntoConstraints = false
        addSubview(content)
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: leadingAnchor),
            content.trailingAnchor.constraint(equalTo: trailingAnchor),
            content.topAnchor.constraint(equalTo: topAnchor),
            content.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    // Height comes from the SwiftUI content's own Auto Layout + flexibleHeight.
    override var intrinsicContentSize: CGSize { .zero }

    /// Fired when the bar's height changes (a dock/pill appearing or resizing) so
    /// the list's bottom inset can be re-synced — otherwise the composer floats
    /// mid-screen with content leaking below it.
    var onHeightChange: ((CGFloat) -> Void)?
    private var lastHeight: CGFloat = 0
    override func layoutSubviews() {
        super.layoutSubviews()
        let delta = bounds.height - lastHeight
        if abs(delta) > 0.5 {
            lastHeight = bounds.height
            onHeightChange?(delta)
        }
    }
}

/// UIKit host for a session's message list + composer. The composer is the view
/// controller's `inputAccessoryView`, so it docks at the bottom and follows the
/// keyboard — including interactive swipe-dismiss — with the system's own
/// animation. The table is full-height; its bottom `contentInset` (and a matching
/// content-offset shift) is derived from the keyboard's overlap, uniformly for
/// every scroll position and both directions.
@MainActor
final class SessionContentController: UIViewController {
    let table = MessageTableView(frame: .zero, style: .plain)
    private let coordinator = MessageListView.Coordinator()
    private let bar: InputBarView
    private let log = Logger(subsystem: "studio.eugenezakharov.shubat", category: "keyboard")

    var messages: [MessageWithParts] = [] {
        didSet { coordinator.receive(messages, table: table) }
    }

    /// Wired from SwiftUI: called with a message id when the user picks "Revert to here".
    var onRevert: ((String) -> Void)? {
        didSet { coordinator.onRevert = onRevert }
    }

    /// Wired from SwiftUI: called with a message id when a row is tapped.
    private var zoomTransition: ZoomTransition?

    /// Presents a message's detail screen, zooming out of the tapped cell.
    private func presentDetail(id: String, from frame: CGRect) {
        guard let message = messages.first(where: { $0.id == id }) else { return }
        let host = UIHostingController(
            rootView: MessageDetailView(message: message, onClose: { [weak self] in
                self?.dismiss(animated: true)
            }))
        host.modalPresentationStyle = .fullScreen
        let transition = ZoomTransition(sourceFrame: frame)
        zoomTransition = transition          // transitioningDelegate is weak
        host.transitioningDelegate = transition
        present(host, animated: true)
    }

    init(bar: InputBarView) {
        self.bar = bar
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var canBecomeFirstResponder: Bool { true }
    override var inputAccessoryView: UIView? { bar }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        table.separatorStyle = .none
        table.backgroundColor = .clear
        table.allowsSelection = true
        table.keyboardDismissMode = .interactive
        table.estimatedRowHeight = 120
        table.register(MessageCell.self, forCellReuseIdentifier: MessageCell.reuseID)
        table.delegate = coordinator
        table.onLayout = { [weak coordinator] in coordinator?.tableDidLayout() }
        coordinator.makeDataSource(for: table)

        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)
        NSLayoutConstraint.activate([
            table.topAnchor.constraint(equalTo: view.topAnchor),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChange(_:)),
                                               name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChange(_:)),
                                               name: UIResponder.keyboardWillHideNotification, object: nil)

        // When the bar's own height changes (a dock appears, the tasks pill shows),
        // re-sync the list inset so the composer stays glued to the bottom.
        bar.onHeightChange = { [weak self] delta in
            self?.syncBottomInset()
            // A big jump — the attachment preview or a dock appearing/disappearing,
            // not per-line text growth — can leave UIKit showing a ghost of the
            // accessory's old frame. Force it to re-lay-out the input accessory.
            if abs(delta) > 40 { self?.reloadInputViews() }
        }
        coordinator.onSelectMessageAt = { [weak self] id, frame in self?.presentDetail(id: id, from: frame) }
    }

    /// Bottom inset the list needs to clear the accessory bar, given the current
    /// keyboard overlap. Called on keyboard changes and whenever the bar resizes.
    /// Keyboard height excluding the accessory bar (0 when the keyboard is down),
    /// so the inset can be recomputed against the *current* bar height.
    private var lastKeyboardOnly: CGFloat = 0
    private func syncBottomInset() {
        let cover = lastKeyboardOnly + bar.bounds.height
        let delta = cover - table.contentInset.bottom
        guard abs(delta) > 0.5 else { return }
        let pinned = coordinator.isPinnedToBottom
        table.contentInset.bottom = cover
        table.verticalScrollIndicatorInsets.bottom = cover
        if pinned {
            let maxY = max(-table.adjustedContentInset.top,
                           table.contentSize.height - table.bounds.height + cover)
            table.contentOffset.y = maxY
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeFirstResponder() // dock the accessory when no field is focused
    }

    /// Inset the table by how much the keyboard (which includes the accessory bar)
    /// overlaps the view, and shift the content by the same delta so the viewport
    /// rides with the keyboard from any position. Idempotent (delta is derived
    /// from the current inset), so duplicate show/hide notifications are no-ops.
    @objc private func keyboardChange(_ note: Notification) {
        guard let window = view.window,
              let end = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
        else { return }
        let endInView = view.convert(end, from: window)
        let kbOverlap = view.bounds.maxY - endInView.minY
        // UIKit posts spurious transitional keyboard frames during an interactive
        // dismiss with an inputAccessoryView — the frame is reported above the view,
        // so the "overlap" spans more than the whole view. Acting on one inflates
        // the bottom inset and shoves the content into a black void below, so drop
        // it. Logged so we can see how often this workaround actually fires.
        guard kbOverlap <= view.bounds.height else {
            log.notice("ignored bogus keyboard frame: overlap=\(Int(kbOverlap)) endY=\(Int(endInView.minY)) viewH=\(Int(self.view.bounds.height))")
            return
        }
        lastKeyboardOnly = max(0, kbOverlap - bar.bounds.height)
        let cover = max(bar.bounds.height, kbOverlap)
        let delta = cover - table.contentInset.bottom
        guard abs(delta) > 0.5 else { return }

        let interactive = table.isTracking || table.isDragging
        let dur = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        let curve = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? UInt) ?? 7
        UIView.animate(withDuration: dur, delay: 0,
                       options: UIView.AnimationOptions(rawValue: curve << 16)) {
            self.table.contentInset.bottom = cover
            self.table.verticalScrollIndicatorInsets.bottom = cover
            // Always keep the offset inside the new valid range. For a programmatic
            // show/hide, also ride the content by the keyboard delta. The clamp is
            // what kills the black gap on an interactive dismiss: when the inset
            // shrinks, an offset left past the new bottom exposes empty space, so
            // pull it back to the bottom (animated, in step with the keyboard).
            let minY = -self.table.adjustedContentInset.top
            let maxY = max(minY, self.table.contentSize.height - self.table.bounds.height + cover)
            let base = interactive ? self.table.contentOffset.y : self.table.contentOffset.y + delta
            self.table.contentOffset.y = min(max(base, minY), maxY)
        }
    }
}

/// Bridges the UIKit session content into SwiftUI, passing the live messages and
/// a SwiftUI bottom bar (docks + composer) hosted as the input accessory.
struct SessionContent<Bar: View>: UIViewControllerRepresentable {
    let messages: [MessageWithParts]
    let revision: Int
    var onRevert: ((String) -> Void)? = nil
    @ViewBuilder var bar: () -> Bar

    func makeUIViewController(context: Context) -> SessionContentController {
        let host = UIHostingController(rootView: bar())
        host.view.backgroundColor = .clear
        // NOTE: do NOT addChild(host) — the accessory view lives in a separate
        // input window, and a child VC's view being moved there crashes UIKit's
        // appearance forwarding. The coordinator retains `host` instead.
        context.coordinator.host = host
        let controller = SessionContentController(bar: InputBarView(content: host.view))
        controller.onRevert = onRevert
        controller.messages = messages
        return controller
    }

    func updateUIViewController(_ controller: SessionContentController, context: Context) {
        context.coordinator.host?.rootView = bar()
        controller.onRevert = onRevert
        controller.messages = messages
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor final class Coordinator { var host: UIHostingController<Bar>? }
}
