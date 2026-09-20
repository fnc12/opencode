import XCTest
@testable import OpenCode

/// The row-height geometry used both by the cell layout and the table's
/// heightForRowAt — they must never disagree, so pin the math.
@MainActor
final class MessageMetricsTests: XCTestCase {
    private let width: CGFloat = 390

    private func text(_ s: String) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [.font: MessageMetrics.bodyFont])
    }

    func testWidthsShrinkFromCellToText() {
        XCTAssertLessThan(MessageMetrics.bubbleWidth(cellWidth: width), width)
        XCTAssertLessThan(MessageMetrics.textWidth(cellWidth: width),
                          MessageMetrics.bubbleWidth(cellWidth: width))
    }

    func testEmptyTextBlockIsZeroHeight() {
        XCTAssertEqual(MessageMetrics.blockHeight(.text(text("")), cellWidth: width), 0)
    }

    func testLongerTextIsTaller() {
        let short = MessageMetrics.blockHeight(.text(text("one line")), cellWidth: width)
        let long = MessageMetrics.blockHeight(
            .text(text(String(repeating: "word ", count: 200))), cellWidth: width)
        XCTAssertGreaterThan(long, short)
    }

    func testImageHeightIsAspectFitAndCapped() {
        let wide = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 100)).image { _ in }
        let h = MessageMetrics.imageHeight(wide, cellWidth: width)
        XCTAssertGreaterThan(h, 0)
        XCTAssertLessThanOrEqual(h, MessageMetrics.maxImageHeight)

        let tall = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 4000)).image { _ in }
        XCTAssertEqual(MessageMetrics.imageHeight(tall, cellWidth: width),
                       MessageMetrics.maxImageHeight, accuracy: 0.5)
    }

    func testBodyHeightSumsBlocksWithGaps() {
        let one = MessageMetrics.bodyHeight([.text(text("a"))], cellWidth: width)
        let two = MessageMetrics.bodyHeight([.text(text("a")), .text(text("b"))], cellWidth: width)
        XCTAssertGreaterThan(two, one, "two blocks + gap must be taller than one")
    }

    func testFullMessageHeightIncludesChrome() {
        let rendered = RenderedMessage(
            roleText: "build", roleColor: .systemGreen, metaText: "1k",
            blocks: [.text(text("hello"))], bubbleColor: .clear)
        let h = MessageMetrics.height(for: rendered, cellWidth: width)
        // Must exceed just the body block (role line + paddings add chrome).
        XCTAssertGreaterThan(h, MessageMetrics.blockHeight(.text(text("hello")), cellWidth: width))
    }
}
