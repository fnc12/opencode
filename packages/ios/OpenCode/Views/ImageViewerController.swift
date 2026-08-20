import UIKit

/// A full-screen viewer for an image attachment (a pasted screenshot). Pinch or
/// double-tap to zoom, drag to pan, tap the ✕ (or swipe down at 1×) to close, and
/// share/save via the button. Presented over the session so tapping a transcript
/// image opens the picture large enough to actually read.
final class ImageViewerController: UIViewController, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    private let image: UIImage
    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    /// The chrome (close/share buttons) — faded out while swiping to dismiss.
    private var chrome: [UIView] = []

    init(image: UIImage) {
        self.image = image
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 6
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.contentInsetAdjustmentBehavior = .never
        // No bounce at rest scale, so a vertical drag reads purely as dismiss.
        scrollView.bounces = false
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)

        // Swipe up OR down (at rest scale) drags the image away and dismisses.
        let dismissPan = UIPanGestureRecognizer(target: self, action: #selector(handleDismissPan(_:)))
        dismissPan.delegate = self
        scrollView.addGestureRecognizer(dismissPan)

        imageView.image = image
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.isUserInteractionEnabled = true
        scrollView.addSubview(imageView)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),
        ])

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        imageView.addGestureRecognizer(doubleTap)

        addCloseButton()
        addShareButton()
    }

    private func addCloseButton() {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "xmark"), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        button.layer.cornerRadius = 18
        button.accessibilityIdentifier = "imageViewer.close"
        button.accessibilityLabel = "Close"
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addAction(UIAction { [weak self] _ in self?.dismiss(animated: true) }, for: .touchUpInside)
        view.addSubview(button)
        chrome.append(button)
        NSLayoutConstraint.activate([
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            button.widthAnchor.constraint(equalToConstant: 36),
            button.heightAnchor.constraint(equalToConstant: 36),
        ])
    }

    private func addShareButton() {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "square.and.arrow.up"), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        button.layer.cornerRadius = 18
        button.accessibilityIdentifier = "imageViewer.share"
        button.accessibilityLabel = "Share"
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addAction(UIAction { [weak self] _ in self?.share() }, for: .touchUpInside)
        view.addSubview(button)
        chrome.append(button)
        NSLayoutConstraint.activate([
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            button.widthAnchor.constraint(equalToConstant: 36),
            button.heightAnchor.constraint(equalToConstant: 36),
        ])
    }

    private func share() {
        let sheet = UIActivityViewController(activityItems: [image], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.maxX - 40, y: 40, width: 1, height: 1)
        present(sheet, animated: true)
    }

    // MARK: swipe to dismiss

    @objc private func handleDismissPan(_ gesture: UIPanGestureRecognizer) {
        // Only at rest scale — when zoomed in, the pan pans the image instead.
        guard scrollView.zoomScale <= scrollView.minimumZoomScale + 0.01 else { return }
        let translation = gesture.translation(in: view)
        switch gesture.state {
        case .changed:
            scrollView.transform = CGAffineTransform(translationX: 0, y: translation.y)
            let progress = min(1, abs(translation.y) / (view.bounds.height * 0.5))
            view.backgroundColor = UIColor.black.withAlphaComponent(1 - progress)
            chrome.forEach { $0.alpha = 1 - progress }
        case .ended, .cancelled:
            let velocity = gesture.velocity(in: view)
            let travelled = abs(translation.y) > view.bounds.height * 0.18 || abs(velocity.y) > 900
            if travelled {
                // Fling off in the drag's direction, then close.
                let direction: CGFloat = (translation.y != 0 ? translation.y : velocity.y) < 0 ? -1 : 1
                UIView.animate(withDuration: 0.2, animations: {
                    self.scrollView.transform = CGAffineTransform(translationX: 0, y: direction * self.view.bounds.height)
                    self.view.backgroundColor = .clear
                    self.chrome.forEach { $0.alpha = 0 }
                }, completion: { _ in self.dismiss(animated: false) })
            } else {
                UIView.animate(withDuration: 0.25) {
                    self.scrollView.transform = .identity
                    self.view.backgroundColor = .black
                    self.chrome.forEach { $0.alpha = 1 }
                }
            }
        default:
            break
        }
    }

    // Let the dismiss pan work alongside the scroll view's own pan.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            let point = gesture.location(in: imageView)
            let scale = scrollView.maximumZoomScale / 2
            let size = CGSize(width: scrollView.bounds.width / scale, height: scrollView.bounds.height / scale)
            let rect = CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                              width: size.width, height: size.height)
            scrollView.zoom(to: rect, animated: true)
        }
    }
}
