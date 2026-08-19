import XCTest
import SwiftUI
@testable import OpenCode

/// Unit coverage for the composer's auto-growing text field: the UITextView
/// subclass's sizing math (min/cap height, scroll-at-cap, re-measure on width
/// change) and the coordinator's change callback. makeUIView/updateUIView are
/// covered by a hosted snapshot in ViewSnapshotTests.
@MainActor
final class GrowingTextViewTests: XCTestCase {
    private func makeView(maxLines: Int = 5) -> HeightTrackingTextView {
        let v = HeightTrackingTextView()
        v.font = .preferredFont(forTextStyle: .body)
        v.textContainerInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        v.maxLines = maxLines
        return v
    }

    private var lineHeight: CGFloat { UIFont.preferredFont(forTextStyle: .body).lineHeight }

    func testEmptyBeforeLayoutReportsOneLineHeight() {
        // No width yet → the one-line floor (lineHeight + 16 insets).
        let v = makeView()
        XCTAssertEqual(v.intrinsicContentSize.height, lineHeight + 16, accuracy: 0.5)
    }

    func testSingleLineStaysAtMinHeight() {
        let v = makeView()
        v.frame = CGRect(x: 0, y: 0, width: 240, height: 40)
        v.text = "hi"
        v.layoutIfNeeded()
        // One line of real text sits at (or a hair above) the one-line floor.
        XCTAssertEqual(v.intrinsicContentSize.height, lineHeight + 16, accuracy: 4.0)
        XCTAssertFalse(v.isScrollEnabled, "one line must not scroll")
    }

    func testGrowsWithMoreLinesUpToCap() {
        let v = makeView(maxLines: 3)
        v.frame = CGRect(x: 0, y: 0, width: 120, height: 40)
        v.text = "one\ntwo"
        v.layoutIfNeeded()
        let two = v.intrinsicContentSize.height
        XCTAssertGreaterThan(two, lineHeight + 16, "two lines must be taller than one")

        // Far exceed the cap → clamps at capHeight and switches to scrolling.
        v.text = Array(repeating: "line", count: 40).joined(separator: "\n")
        v.layoutIfNeeded()
        let capped = v.intrinsicContentSize.height
        let capHeight = lineHeight * 3 + 16
        XCTAssertEqual(capped, capHeight, accuracy: 1.5, "height clamps at maxLines")
        XCTAssertTrue(v.isScrollEnabled, "beyond the cap the field scrolls")
    }

    func testWidthChangeReMeasures() {
        let v = makeView(maxLines: 5)
        // A sentence that wraps to two lines when narrow, one when wide.
        v.text = "the quick brown fox jumps over the lazy dog"
        v.frame = CGRect(x: 0, y: 0, width: 90, height: 40)
        v.layoutIfNeeded()
        let narrow = v.intrinsicContentSize.height
        v.frame = CGRect(x: 0, y: 0, width: 600, height: 40)
        v.layoutSubviews() // width change → invalidate + re-measure
        let wide = v.intrinsicContentSize.height
        XCTAssertGreaterThan(narrow, wide, "narrower width wraps to a taller field")
    }

    func testCoordinatorForwardsTextChange() {
        var bound = "start"
        let binding = Binding(get: { bound }, set: { bound = $0 })
        let gtv = GrowingTextView(text: binding, placeholder: "Type…")
        let coordinator = gtv.makeCoordinator()
        let placeholder = UILabel()
        coordinator.placeholderLabel = placeholder
        let tv = UITextView()
        tv.text = "edited"
        coordinator.textViewDidChange(tv)
        XCTAssertEqual(bound, "edited", "the binding follows the text view")
        XCTAssertTrue(placeholder.isHidden, "placeholder hides once there's text")
    }
}
