import UIKit

/// A "zoom from the tapped cell" modal transition: the detail scales up out of
/// the cell's frame into a full screen, and collapses back on dismiss.
final class ZoomTransition: NSObject, UIViewControllerTransitioningDelegate {
    private let sourceFrame: CGRect
    init(sourceFrame: CGRect) { self.sourceFrame = sourceFrame }

    func animationController(forPresented presented: UIViewController,
                             presenting: UIViewController,
                             source: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        ZoomAnimator(sourceFrame: sourceFrame, presenting: true)
    }

    func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        ZoomAnimator(sourceFrame: sourceFrame, presenting: false)
    }
}

private final class ZoomAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    private let sourceFrame: CGRect
    private let presenting: Bool
    init(sourceFrame: CGRect, presenting: Bool) {
        self.sourceFrame = sourceFrame
        self.presenting = presenting
    }

    func transitionDuration(using context: UIViewControllerContextTransitioning?) -> TimeInterval { 0.34 }

    func animateTransition(using context: UIViewControllerContextTransitioning) {
        let container = context.containerView

        if presenting {
            guard let toVC = context.viewController(forKey: .to),
                  let toView = context.view(forKey: .to) else {
                context.completeTransition(false); return
            }
            let finalFrame = context.finalFrame(for: toVC)
            container.addSubview(toView)
            toView.frame = sourceFrame
            toView.layer.cornerRadius = 16
            toView.clipsToBounds = true
            toView.alpha = 0.35
            UIView.animate(withDuration: transitionDuration(using: context), delay: 0,
                           usingSpringWithDamping: 0.86, initialSpringVelocity: 0.4,
                           options: [.curveEaseOut]) {
                toView.frame = finalFrame
                toView.layer.cornerRadius = 0
                toView.alpha = 1
            } completion: { _ in
                context.completeTransition(!context.transitionWasCancelled)
            }
        } else {
            guard let fromView = context.view(forKey: .from) else {
                context.completeTransition(false); return
            }
            UIView.animate(withDuration: transitionDuration(using: context), delay: 0,
                           options: [.curveEaseIn]) {
                fromView.frame = self.sourceFrame
                fromView.layer.cornerRadius = 16
                fromView.alpha = 0.2
            } completion: { _ in
                fromView.removeFromSuperview()
                context.completeTransition(!context.transitionWasCancelled)
            }
        }
    }
}
