import XCTest
@testable import OpenCode

/// Guards the "empty You bubble" regression: a message whose only part is
/// synthetic/ignored (or empty) must not be considered renderable, so the list
/// skips it. See `MessageWithParts.hasRenderableContent`.
final class MessageRenderingTests: XCTestCase {
    private func decode(_ json: String) throws -> MessageWithParts {
        try JSONDecoder().decode(MessageWithParts.self, from: Data(json.utf8))
    }

    private func userMessage(parts: String) -> String {
        """
        {"info":{"id":"m","sessionID":"s","role":"user","time":{"created":1}},"parts":[\(parts)]}
        """
    }
    private func assistantMessage(parts: String) -> String {
        """
        {"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1},"modelID":"x","providerID":"y","agent":"build","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[\(parts)]}
        """
    }

    func testSyntheticOnlyMessageIsHidden() throws {
        // The exact filler POST /session/:id/shell posts as a user message.
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","synthetic":true,"text":"The following tool was executed by the user"}
        """
        XCTAssertFalse(try decode(userMessage(parts: part)).hasRenderableContent)
    }

    func testIgnoredOnlyMessageIsHidden() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","ignored":true,"text":"hidden"}
        """
        XCTAssertFalse(try decode(userMessage(parts: part)).hasRenderableContent)
    }

    func testEmptyTextMessageIsHidden() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","text":"   "}
        """
        XCTAssertFalse(try decode(assistantMessage(parts: part)).hasRenderableContent)
    }

    func testRealTextMessageIsVisible() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"text","text":"hello"}
        """
        XCTAssertTrue(try decode(userMessage(parts: part)).hasRenderableContent)
    }

    func testToolOnlyMessageIsVisible() throws {
        let part = """
        {"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed"}}
        """
        XCTAssertTrue(try decode(assistantMessage(parts: part)).hasRenderableContent)
    }

    func testSyntheticTextButRealToolIsVisible() throws {
        let parts = """
        {"id":"p1","sessionID":"s","messageID":"m","type":"text","synthetic":true,"text":"filler"},
        {"id":"p2","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed"}}
        """
        XCTAssertTrue(try decode(assistantMessage(parts: parts)).hasRenderableContent)
    }

    // MARK: image attachments (viewable screenshots in the transcript)

    /// A tiny valid PNG as a `data:` URL — the shape the server sends for a
    /// pasted screenshot (pixels embedded, no fetch).
    @MainActor private func pngDataURL() -> String {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 3))
        let image = renderer.image { ctx in
            UIColor.systemTeal.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 4, height: 3))
        }
        let b64 = image.pngData()!.base64EncodedString()
        return "data:image/png;base64,\(b64)"
    }

    @MainActor func testDecodesImageDataURL() throws {
        let image = MessageListView.Coordinator.decodeDataURLImage(pngDataURL())
        XCTAssertNotNil(image)
        // Decoded at scale 1, so size is the pixel dimensions (the renderer may
        // bake in the device scale); assert the 4:3 aspect ratio survived.
        let size = try XCTUnwrap(image).size
        XCTAssertGreaterThan(size.width, 0)
        XCTAssertEqual(size.width / size.height, 4.0 / 3.0, accuracy: 0.01)
    }

    @MainActor func testImageAttachmentFromImageMime() {
        let file = FileRefContent(filename: "shot.png", url: pngDataURL(), mime: "image/png")
        XCTAssertNotNil(MessageListView.Coordinator.imageAttachment(file))
    }

    @MainActor func testImageAttachmentInfersImageFromDataURLWithoutMime() {
        let file = FileRefContent(filename: "shot.png", url: pngDataURL(), mime: nil)
        XCTAssertNotNil(MessageListView.Coordinator.imageAttachment(file))
    }

    @MainActor func testNonImageFileIsNotAnAttachmentImage() {
        // A code-reference file part (what `@file:line` mentions look like) is not
        // an image and must fall back to the text chip.
        let file = FileRefContent(filename: "main.swift", url: "file:///main.swift", mime: "text/x-swift")
        XCTAssertNil(MessageListView.Coordinator.imageAttachment(file))
    }

    @MainActor func testMalformedDataURLDecodesToNil() {
        XCTAssertNil(MessageListView.Coordinator.decodeDataURLImage("data:image/png;base64,not$$base64!!"))
        XCTAssertNil(MessageListView.Coordinator.decodeDataURLImage("data:image/png,rawnotbase64"))
        XCTAssertNil(MessageListView.Coordinator.decodeDataURLImage("https://example.com/a.png"))
    }

    // MARK: image renders in the cell (view layer, deterministic — no server)

    @MainActor private func image(_ w: CGFloat, _ h: CGFloat) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: w, height: h)).image { ctx in
            UIColor.systemTeal.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        }
    }

    /// Recursively finds the first UIButton carrying the given accessibility id.
    @MainActor private func button(in view: UIView, id: String) -> UIButton? {
        for sub in view.subviews {
            if let b = sub as? UIButton, b.accessibilityIdentifier == id { return b }
            if let found = button(in: sub, id: id) { return found }
        }
        return nil
    }

    /// An `.image` block must render a real, tappable image button sized to the
    /// computed block height — and tapping it must fire `onSelectImage` (which
    /// opens the full-screen viewer). Guards the "can't view the screenshot" fix.
    @MainActor func testImageBlockRendersTappableImageInCell() {
        let img = image(400, 300)
        let rendered = RenderedMessage(
            roleText: "You", roleColor: .systemBlue, metaText: nil,
            blocks: [.image(img)], bubbleColor: .clear)
        let width: CGFloat = 390

        let cell = MessageCell(style: .default, reuseIdentifier: nil)
        var tapped: UIImage?
        cell.onSelectImage = { tapped = $0 }
        cell.configure(rendered)
        cell.frame = CGRect(x: 0, y: 0, width: width,
                            height: MessageMetrics.height(for: rendered, cellWidth: width))
        cell.setNeedsLayout(); cell.layoutIfNeeded()

        let imageButton = button(in: cell.contentView, id: "message.image")
        XCTAssertNotNil(imageButton, "an image block must render as a tappable image, not a text chip")
        XCTAssertEqual(imageButton?.currentImage?.size, img.size, "the button shows the attachment image")
        XCTAssertEqual(imageButton?.bounds.height ?? 0,
                       MessageMetrics.blockHeight(.image(img), cellWidth: width), accuracy: 1,
                       "the image is laid out at the computed (aspect-fit, capped) height")

        imageButton?.sendActions(for: .touchUpInside)
        XCTAssertEqual(tapped, img, "tapping the image opens the viewer with that image")
    }

    /// A tall screenshot's inline thumbnail is capped so it can't take over the
    /// transcript (the full picture is available in the full-screen viewer).
    @MainActor func testTallImageThumbnailIsCapped() {
        let tall = image(300, 3000)
        let h = MessageMetrics.blockHeight(.image(tall), cellWidth: 390)
        XCTAssertEqual(h, MessageMetrics.maxImageHeight, accuracy: 0.5, "a very tall image is capped")

        let wide = image(300, 60) // 5:1 — well under the cap
        let hw = MessageMetrics.blockHeight(.image(wide), cellWidth: 390)
        XCTAssertLessThan(hw, MessageMetrics.maxImageHeight, "a short image keeps its aspect height")
        XCTAssertGreaterThan(hw, 0)
    }
}
