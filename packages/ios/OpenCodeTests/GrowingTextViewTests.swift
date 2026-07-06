import XCTest
import UIKit
@testable import OpenCode

/// The composer's growing field must report the *right* height on the same pass
/// a new line appears — the regression was a two-line entry staying one line tall
/// (text vertically off-centre, "asymmetric") until an unrelated relayout. The
/// height now comes from the text view's own laid-out width, so it's correct
/// immediately, which these tests pin down.
@MainActor
final class GrowingTextViewTests: XCTestCase {
    private func makeView(width: CGFloat, maxLines: Int = 5) -> HeightTrackingTextView {
        let view = HeightTrackingTextView()
        view.maxLines = maxLines
        view.font = .preferredFont(forTextStyle: .body)
        view.textContainerInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        view.isScrollEnabled = false
        view.frame = CGRect(x: 0, y: 0, width: width, height: 40)
        view.layoutIfNeeded()
        return view
    }

    private var lineHeight: CGFloat { UIFont.preferredFont(forTextStyle: .body).lineHeight }

    func testHeightGrowsWhenTextWrapsToSecondLine() {
        let view = makeView(width: 220)

        view.text = "hi"
        view.layoutIfNeeded()
        let oneLine = view.intrinsicContentSize.height

        // Long enough to wrap past one line at this width.
        view.text = String(repeating: "wrap ", count: 30)
        view.layoutIfNeeded()
        let wrapped = view.intrinsicContentSize.height

        XCTAssertGreaterThan(wrapped, oneLine + lineHeight * 0.5,
            "field must grow ~a line when the text wraps (one=\(oneLine) wrapped=\(wrapped))")
    }

    func testSingleLineHeightMeasuredFromOwnWidthNotScreen() {
        // The old bug: with no SwiftUI width proposal the field measured against
        // the full screen width, so a line that actually wraps looked like one
        // line. Measuring from the view's own (narrow) bounds, a short string is
        // one line — and a long string at the same width is taller.
        let view = makeView(width: 140)

        view.text = "hello"
        view.layoutIfNeeded()
        let oneLine = view.intrinsicContentSize.height

        // One short line stays clearly under a two-line field.
        let twoLineFloor = lineHeight * 2 + view.textContainerInset.top + view.textContainerInset.bottom
        XCTAssertLessThan(oneLine, twoLineFloor - lineHeight * 0.5,
            "a short string must not inflate to two lines (\(oneLine))")

        view.text = "this is clearly more text than fits on a single narrow line"
        view.layoutIfNeeded()
        XCTAssertGreaterThan(view.intrinsicContentSize.height, oneLine + lineHeight * 0.5,
            "wrapping text at a narrow width must be taller than one line")
    }

    func testCapsAtMaxLinesThenScrolls() {
        let view = makeView(width: 200, maxLines: 3)
        view.text = String(repeating: "line\n", count: 12)
        view.layoutIfNeeded()

        let cap = lineHeight * 3 + view.textContainerInset.top + view.textContainerInset.bottom
        XCTAssertEqual(view.intrinsicContentSize.height, cap, accuracy: 1.0,
            "must stop growing at maxLines")
        XCTAssertTrue(view.isScrollEnabled, "must scroll once past the cap")
    }
}
