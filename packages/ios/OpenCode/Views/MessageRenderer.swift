import UIKit

/// The message → renderable-blocks pipeline, extracted from the `MessageListView`
/// UITableView coordinator so it's pure, testable logic (the UITableView itself
/// is UI shell that xccov can't count). Turns a `MessageWithParts` into a
/// `RenderedMessage` of text/table/code/image blocks, and computes the content
/// fingerprint used to re-render only changed rows.
@MainActor
enum MessageRenderer {
    static let bodyFont = MessageMetrics.bodyFont

    static func render(_ message: MessageWithParts) -> RenderedMessage {
        var blocks: [MessageBlock] = []

        for part in message.parts {
            // Hidden parts (synthetic tool-call narration, server-ignored) —
            // matches the web client's `!synthetic && !ignored` filter.
            guard part.isVisible else { continue }
            switch part.content {
            case .text(let text) where !text.isEmpty:
                blocks.append(contentsOf: textToBlocks(text))
            case .reasoning(let text) where !text.isEmpty:
                blocks.append(contentsOf: reasoningBlocks(text))
            case .tool(let tool):
                blocks.append(.text(toolLine(tool)))
            case .patch(let patch):
                blocks.append(.text(patchLine(patch)))
            case .file(let file):
                // A pasted screenshot / image attachment: show it inline
                // (tappable for a full-screen view). Non-images (or an image
                // we can't decode) fall back to the compact filename chip.
                if let image = imageAttachment(file) {
                    blocks.append(.image(image))
                } else {
                    blocks.append(.text(fileChip(file)))
                }
            case .compaction(let auto):
                let para = NSMutableParagraphStyle(); para.alignment = .center
                blocks.append(.text(NSAttributedString(
                    string: auto ? "⸻  earlier context summarized  ⸻" : "⸻  context summarized  ⸻",
                    attributes: [.font: UIFont.systemFont(ofSize: 11, weight: .medium),
                                 .foregroundColor: UIColor.tertiaryLabel, .paragraphStyle: para])))
            case .stepStart(let step):
                if let title = step.title, !title.isEmpty {
                    blocks.append(.text(NSAttributedString(string: title, attributes: [
                        .font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.secondaryLabel])))
                }
            case .text, .reasoning, .stepFinish, nil:
                break
            }
        }

        switch message.info {
        case .user:
            return RenderedMessage(
                roleText: "You", roleColor: .systemBlue, metaText: nil,
                blocks: blocks, bubbleColor: UIColor.systemBlue.withAlphaComponent(0.12))
        case .assistant(let info):
            if let error = info.error {
                blocks.append(.text(NSAttributedString(string: "Error: \(error.displayText)", attributes: [
                    .font: bodyFont, .foregroundColor: UIColor.systemOrange])))
            }
            return RenderedMessage(
                roleText: info.agent, roleColor: .systemGreen, metaText: tokenSummary(info),
                blocks: blocks, bubbleColor: UIColor.white.withAlphaComponent(0.06))
        }
    }

    /// Reasoning is collapsed to a one-line "💭 Thinking" marker on the phone —
    /// the full text is available by tapping into the message detail.
    static func reasoningBlocks(_ text: String) -> [MessageBlock] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        return [.text(NSAttributedString(string: "💭 Thinking", attributes: [
            .font: UIFont.systemFont(ofSize: bodyFont.pointSize - 1, weight: .medium),
            .foregroundColor: UIColor.tertiaryLabel]))]
    }

    /// Splits a text part into blocks: bounded markdown paragraphs, with any GFM
    /// table lifted out as a structured `.table` block and fenced code as `.code`.
    static func textToBlocks(_ text: String) -> [MessageBlock] {
        var out: [MessageBlock] = []
        var textBuf: [String] = []
        func flushText() {
            guard !textBuf.isEmpty else { return }
            let joined = textBuf.joined(separator: "\n")
            textBuf.removeAll()
            for paragraph in joined.components(separatedBy: "\n\n") {
                let trimmed = paragraph.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                for chunk in boundedChunks(trimmed, maxChars: 1500) {
                    out.append(.text(MarkdownRenderer.attributed(chunk, font: bodyFont, color: .label)))
                }
            }
        }
        let lines = text.components(separatedBy: "\n")
        var i = 0
        while i < lines.count {
            let trimmed = lines[i].trimmingCharacters(in: .whitespaces)
            // Fenced code block: ```lang … ``` — checked before tables (code may
            // contain `|`). Lift it out as a highlighted `.code` block.
            if trimmed.hasPrefix("```") {
                flushText()
                let lang = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                var codeLines: [String] = []
                i += 1
                while i < lines.count, !lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                    codeLines.append(lines[i]); i += 1
                }
                if i < lines.count { i += 1 } // consume the closing ```
                out.append(.code(codeBlock(codeLines.joined(separator: "\n"), lang: lang)))
                continue
            }
            let next = i + 1 < lines.count ? lines[i + 1] : nil
            if MarkdownTable.isStart(lines[i], next: next) {
                flushText()
                var rows: [String] = []
                while i < lines.count, lines[i].contains("|") { rows.append(lines[i]); i += 1 }
                if let table = MarkdownTable.parse(rows) { out.append(.table(table)) }
                continue
            }
            textBuf.append(lines[i]); i += 1
        }
        flushText()
        return out
    }

    /// Syntax-highlights a fenced code block; falls back to plain monospace.
    static func codeBlock(_ code: String, lang: String) -> NSAttributedString {
        let dark = UITraitCollection.current.userInterfaceStyle == .dark
        if let hl = SyntaxHighlighter.attributed(code, language: lang, dark: dark, fontSize: CodeBlockView.fontSize) {
            return hl
        }
        return NSAttributedString(string: code,
                                  attributes: [.font: CodeBlockView.font, .foregroundColor: UIColor.label])
    }

    /// Breaks a very long block on line boundaries so each piece stays bounded.
    static func boundedChunks(_ s: String, maxChars: Int) -> [String] {
        guard s.count > maxChars else { return [s] }
        var chunks: [String] = []
        var current = ""
        for line in s.split(separator: "\n", omittingEmptySubsequences: false) {
            if !current.isEmpty && current.count + line.count > maxChars {
                chunks.append(current)
                current = ""
            }
            current += (current.isEmpty ? "" : "\n") + line
        }
        if !current.isEmpty { chunks.append(current) }
        return chunks
    }

    static func toolLine(_ tool: ToolContent) -> NSAttributedString {
        let mark: String
        let color: UIColor
        switch tool.state.status {
        case "completed": mark = "✓"; color = .systemGreen
        case "running": mark = "…"; color = .systemBlue
        case "error": mark = "✕"; color = .systemRed
        case "pending": mark = "◷"; color = .systemOrange
        default: mark = "▸"; color = .secondaryLabel
        }

        let (label, detail) = ToolDisplay.describe(tool)
        let line = NSMutableAttributedString(
            string: "\(mark) \(label)",
            attributes: [.font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 1, weight: .semibold),
                         .foregroundColor: color])
        if let detail, !detail.isEmpty {
            line.append(NSAttributedString(string: "  \(detail)", attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 1, weight: .regular),
                .foregroundColor: UIColor.secondaryLabel]))
        }
        // Only an error is worth surfacing inline (never the full output).
        if tool.state.status == "error", let err = (tool.state.error ?? tool.state.output), !err.isEmpty {
            let snippet = err.count > 200 ? String(err.prefix(200)) + "…" : err
            line.append(NSAttributedString(string: "\n\(snippet)", attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 2, weight: .regular),
                .foregroundColor: UIColor.systemRed]))
        }
        return line
    }

    /// A `patch` part: "⌥ Patch  N files" (the committed change snapshot).
    static func patchLine(_ patch: PatchContent) -> NSAttributedString {
        let line = NSMutableAttributedString(
            string: "⌥ Patch",
            attributes: [.font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 1, weight: .semibold),
                         .foregroundColor: UIColor.systemPurple])
        line.append(NSAttributedString(string: "  \(PatchDisplay.summary(patch))", attributes: [
            .font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 1, weight: .regular),
            .foregroundColor: UIColor.secondaryLabel]))
        return line
    }

    /// A `file` part: a compact context chip "📎 filename:line".
    static func fileChip(_ file: FileRefContent) -> NSAttributedString {
        NSAttributedString(string: "📎 \(FileRefDisplay.chip(file))", attributes: [
            .font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 1, weight: .regular),
            .foregroundColor: UIColor.systemTeal])
    }

    /// Decodes an image `file` part to a `UIImage`, or nil if not an image / can't
    /// decode. Attachments embed the pixels in a `data:image/…;base64,…` URL.
    static func imageAttachment(_ file: FileRefContent) -> UIImage? {
        let isImage = (file.mime?.hasPrefix("image/") ?? false)
            || (file.url?.hasPrefix("data:image/") ?? false)
        guard isImage, let url = file.url else { return nil }
        return decodeDataURLImage(url)
    }

    /// `data:[<mime>][;base64],<payload>` → UIImage. Base64 data URLs only.
    static func decodeDataURLImage(_ url: String) -> UIImage? {
        guard url.hasPrefix("data:"),
              let comma = url.firstIndex(of: ","),
              url[url.startIndex..<comma].contains(";base64") else { return nil }
        let b64 = String(url[url.index(after: comma)...])
        guard let data = Data(base64Encoded: b64, options: .ignoreUnknownCharacters) else { return nil }
        return UIImage(data: data)
    }

    static func tokenSummary(_ info: AssistantMessage) -> String {
        func fmt(_ n: Int) -> String { n >= 1000 ? "\(n / 1000)k" : "\(n)" }
        return "\(fmt(info.tokens.input))→\(fmt(info.tokens.output))"
    }

    /// A cheap fingerprint of a message's visible content — changes when streamed
    /// text grows, a tool's status/output changes, or it completes.
    static func signature(_ message: MessageWithParts) -> Int {
        var hasher = Hasher()
        hasher.combine(message.id)
        for part in message.parts {
            hasher.combine(part.id)
            switch part.content {
            case .text(let text): hasher.combine(text.count)
            case .reasoning(let text): hasher.combine(text.count)
            case .tool(let tool): hasher.combine(tool.state.status); hasher.combine(tool.state.title); hasher.combine(tool.tool)
            case .patch(let patch): hasher.combine(patch.files.count); hasher.combine(patch.hash)
            case .file(let file): hasher.combine(file.filename); hasher.combine(file.url)
            case .compaction(let auto): hasher.combine("compaction"); hasher.combine(auto)
            case .stepStart(let step): hasher.combine(step.title)
            case .stepFinish, nil: break
            }
        }
        if case .assistant(let info) = message.info {
            hasher.combine(info.time.completed)
            hasher.combine(info.tokens.output)
            hasher.combine(info.error?.displayText)
        }
        return hasher.finalize()
    }
}
