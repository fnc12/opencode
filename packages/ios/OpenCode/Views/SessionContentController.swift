import UIKit
import SwiftUI
import os

/// Self-sizing container so a SwiftUI-hosted composer can serve as a view
/// controller's `inputAccessoryView` — docking at the bottom and riding the
/// keyboard (including interactive dismiss) natively, no manual tracking.
final class InputBarView: UIView {
    private let content: UIView
    init(content: UIView) {
        self.content = content
        super.init(frame: CGRect(x: 0, y: 0, width: 0, height: 44))
        autoresizingMask = .flexibleHeight
        content.translatesAutoresizingMaskIntoConstraints = false
        addSubview(content)
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: leadingAnchor),
            content.trailingAnchor.constraint(equalTo: trailingAnchor),
            content.topAnchor.constraint(equalTo: topAnchor),
            // Pin to the SAFE-AREA bottom, not the raw bottom: it's the home
            // indicator inset (34pt) when docked and 0 when riding the keyboard,
            // so the composer clears the home indicator without leaving a gap
            // above the keyboard.
            content.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    /// The height the content actually wants. `flexibleHeight` alone only ever
    /// *grows* the accessory, so removing an attachment chip left it too tall
    /// with a phantom gap. The SwiftUI host reports its content height as its
    /// `intrinsicContentSize` (it's created with `sizingOptions = .intrinsicContentSize`),
    /// so forwarding that lets the accessory shrink too.
    private func fittingHeight() -> CGFloat {
        let h = content.intrinsicContentSize.height
        return h > 0 ? h : bounds.height
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: fittingHeight())
    }

    /// Fired when the bar's height changes (a dock/pill/attachment appearing or
    /// disappearing) so the list's bottom inset can be re-synced.
    var onHeightChange: ((CGFloat) -> Void)?
    private var lastHeight: CGFloat = 0
    override func layoutSubviews() {
        super.layoutSubviews()
        // Ask UIKit to re-size the accessory when the content wants a different
        // height than we currently have — this is what makes it *shrink*.
        if abs(fittingHeight() - bounds.height) > 0.5 {
            invalidateIntrinsicContentSize()
        }
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

    /// Thinking indicator shown as the table's footer — i.e. right after the last
    /// message, where the reply will stream in — not a fixed overlay that lands on
    /// top of an existing message.
    var showTyping: Bool = false {
        didSet { if showTyping != oldValue { updateTypingFooter() } }
    }
    private var typingFooterHost: UIViewController?
    private func updateTypingFooter() {
        guard showTyping else { table.tableFooterView = nil; typingFooterHost = nil; return }
        let host = UIHostingController(rootView:
            HStack { TypingIndicator(); Spacer() }
                .padding(.horizontal, 16).padding(.vertical, 10))
        host.view.backgroundColor = .clear
        let width = table.bounds.width > 0 ? table.bounds.width : UIScreen.main.bounds.width
        let height = host.view.systemLayoutSizeFitting(
            CGSize(width: width, height: 0),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel).height
        host.view.frame = CGRect(x: 0, y: 0, width: width, height: height)
        host.view.isAccessibilityElement = true
        host.view.accessibilityIdentifier = "typing.indicator"
        table.tableFooterView = host.view
        typingFooterHost = host
        // Keep the fresh indicator on screen when the user is at the bottom.
        if coordinator.isPinnedToBottom {
            DispatchQueue.main.async { [weak self] in self?.scrollToBottom(animated: false) }
            reassertBottomSoon()
        }
    }
    private func scrollToBottom(animated: Bool = true) {
        let cover = table.contentInset.bottom
        let maxY = max(-table.adjustedContentInset.top,
                       table.contentSize.height - table.bounds.height + cover)
        table.setContentOffset(CGPoint(x: 0, y: maxY), animated: animated)
    }

    /// Re-assert the pinned-to-bottom position a moment after a keyboard/accessory
    /// resize. On some devices the bottom inset + content size settle a frame or
    /// two late after sending a multi-line message (the composer shrinks tall→one
    /// line), which briefly left the newest message + typing dots ducked under the
    /// composer. A delayed, no-op-if-already-there re-scroll cleans that up.
    private func reassertBottomSoon() {
        guard coordinator.isPinnedToBottom else { return }
        for delay in [0.05, 0.35] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, self.coordinator.isPinnedToBottom,
                      !self.table.isTracking, !self.table.isDragging else { return }
                self.scrollToBottom(animated: false)
            }
        }
    }

    /// Wired from SwiftUI: called with a message id when the user picks "Revert to here".
    var onRevert: ((String) -> Void)? {
        didSet { coordinator.onRevert = onRevert }
    }

    /// Wired from SwiftUI: called as the list nears the top so older history pages
    /// in (scroll-up pagination).
    var onLoadOlder: (() -> Void)? {
        didSet { coordinator.onNearTop = onLoadOlder }
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
        // .overFullScreen (not .fullScreen) keeps the message list rendered
        // behind the detail, so dismissing zooms back onto it instead of flashing
        // a blank screen while the list re-loads.
        host.modalPresentationStyle = .overFullScreen
        host.view.backgroundColor = .systemBackground // opaque over the list
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
        bar.onHeightChange = { [weak self] _ in self?.syncBottomInset() }
        coordinator.onSelectMessageAt = { [weak self] id, frame in self?.presentDetail(id: id, from: frame) }
    }

    /// Bottom inset the list needs to clear the accessory bar, given the current
    /// keyboard overlap. Called on keyboard changes and whenever the bar resizes.
    /// Keyboard height excluding the accessory bar (0 when the keyboard is down),
    /// so the inset can be recomputed against the *current* bar height.
    private var lastKeyboardOnly: CGFloat = 0
    private func syncBottomInset() {
        // Re-derive the keyboard part of the cover from the bar's ACTUAL on-screen
        // position instead of trusting the last keyboard notification. The bar is
        // the accessory view riding the keyboard, so `view.maxY - barTop` IS the
        // real covered height. Measured on device: an interactive dismiss can end
        // with a bogus (or missing) keyboard frame that our guard drops, leaving
        // `lastKeyboardOnly` stuck at a transitional value — a phantom bottom
        // inset that rested the list ~40-50pt above the composer with the
        // keyboard closed (while opening the keyboard set an honest inset, which
        // is why the two modes rested differently). The bar re-docks after any
        // dismiss, its height change lands here, and this self-corrects.
        if let barSuper = bar.superview, bar.window != nil, view.window != nil {
            let barTopInView = view.convert(bar.frame, from: barSuper).minY
            lastKeyboardOnly = max(0, view.bounds.maxY - barTopInView - bar.bounds.height)
        }
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
            reassertBottomSoon()
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
        // A hide's end frame is untrustworthy (measured: bogus transitional
        // frames during interactive dismiss) — hidden means the docked bar is
        // the whole cover, full stop.
        let kbOverlap = note.name == UIResponder.keyboardWillHideNotification
            ? bar.bounds.height
            : view.bounds.maxY - endInView.minY
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
        // The composer (accessory) may still be settling its height after this
        // frame (e.g. shrinking from multi-line to one line on send); re-assert
        // the bottom so the newest content never stays ducked under it.
        if !interactive { reassertBottomSoon() }
    }
}

/// Bridges the UIKit session content into SwiftUI, passing the live messages and
/// a SwiftUI bottom bar (docks + composer) hosted as the input accessory.
struct SessionContent<Bar: View>: UIViewControllerRepresentable {
    let messages: [MessageWithParts]
    let revision: Int
    var onRevert: ((String) -> Void)? = nil
    /// Called as the list nears the top so the view can page in older history.
    var onLoadOlder: (() -> Void)? = nil
    /// Shows a "thinking" indicator as the last row (in the message flow, where
    /// the reply will appear) while the agent works but hasn't streamed text yet.
    var showTyping: Bool = false
    /// Changes only when the bar's *inputs* change (busy state, docks, pickers) —
    /// NOT on every streamed message delta. The hosted composer is only rebuilt
    /// when this changes, so a fast stream doesn't churn the accessory (which was
    /// making it flicker its safe-area height and shove content under the bar).
    /// The composer's own typing state updates inside its hosting controller and
    /// doesn't need a rootView reassign.
    var barRevision: Int = 0
    @ViewBuilder var bar: () -> Bar

    func makeUIViewController(context: Context) -> SessionContentController {
        let host = UIHostingController(rootView: bar())
        host.view.backgroundColor = .clear
        // Report the SwiftUI content's height as the view's intrinsicContentSize
        // so the input accessory can shrink (not just grow) as the composer's
        // attachment preview / docks appear and disappear.
        host.sizingOptions = .intrinsicContentSize
        // NOTE: do NOT addChild(host) — the accessory view lives in a separate
        // input window, and a child VC's view being moved there crashes UIKit's
        // appearance forwarding. The coordinator retains `host` instead.
        context.coordinator.host = host
        context.coordinator.lastBarRevision = barRevision
        let controller = SessionContentController(bar: InputBarView(content: host.view))
        controller.onRevert = onRevert
        controller.onLoadOlder = onLoadOlder
        controller.messages = messages
        controller.showTyping = showTyping
        return controller
    }

    func updateUIViewController(_ controller: SessionContentController, context: Context) {
        // Only rebuild the hosted composer when its inputs actually changed — a
        // streamed delta bumps `revision` (→ new messages/showTyping) but leaves
        // `barRevision` alone, so the accessory stops flickering mid-stream.
        if context.coordinator.lastBarRevision != barRevision {
            context.coordinator.lastBarRevision = barRevision
            context.coordinator.host?.rootView = bar()
        }
        controller.onRevert = onRevert
        controller.onLoadOlder = onLoadOlder
        controller.messages = messages
        controller.showTyping = showTyping
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor final class Coordinator {
        var host: UIHostingController<Bar>?
        var lastBarRevision: Int = 0
    }
}
