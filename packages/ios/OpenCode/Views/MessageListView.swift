import SwiftUI
import UIKit

/// The conversation list as a hand-tuned `UITableView` — custom cells, manually
/// computed + cached row heights, no SwiftUI hosting in cells and no bindings.
/// Markdown is rendered to `NSAttributedString` once per content change; while a
/// message streams we `reconfigureItems` only that one row and recompute only
/// its height.
/// UITableView that notifies on every layout pass, so the list can re-pin to the
/// newest message when its frame changes (keyboard show/hide, rotation, load).
final class MessageTableView: UITableView {
    var onLayout: (() -> Void)?
    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

struct MessageListView: UIViewRepresentable {
    let messages: [MessageWithParts]
    /// Bumped by the store on every change; ties SwiftUI's `updateUIView` to data updates.
    let revision: Int

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UITableView {
        let table = MessageTableView(frame: .zero, style: .plain)
        table.separatorStyle = .none
        table.backgroundColor = .clear
        table.allowsSelection = false
        table.keyboardDismissMode = .interactive
        table.estimatedRowHeight = 120
        table.register(MessageCell.self, forCellReuseIdentifier: MessageCell.reuseID)
        table.contentInset = UIEdgeInsets(top: 6, left: 0, bottom: 6, right: 0)
        table.delegate = context.coordinator
        // Re-pin to the newest message whenever the table re-lays out — first
        // load, keyboard show/hide (the frame shrinks/grows), rotation.
        table.onLayout = { [weak coordinator = context.coordinator] in coordinator?.tableDidLayout() }
        context.coordinator.makeDataSource(for: table)
        return table
    }

    func updateUIView(_ table: UITableView, context: Context) {
        context.coordinator.receive(messages, table: table)
    }

    @MainActor
    final class Coordinator: NSObject, UITableViewDelegate {
        private var dataSource: UITableViewDiffableDataSource<Int, String>?
        private var lookup: [String: MessageWithParts] = [:]
        private var rendered: [String: (signature: Int, message: RenderedMessage)] = [:]
        private var heightCache: [String: CGFloat] = [:]
        var hasApplied = false
        private weak var table: UITableView?
        /// Latest messages held back while the user is actively scrolling (#1).
        private var pending: [MessageWithParts]?
        /// Whether the list should stay pinned to the newest message. At the
        /// bottom the scroll view already keeps the last row visible as the frame
        /// shrinks for the keyboard, so the controller only shifts when this is false.
        private var pinnedToBottom = true
        var isPinnedToBottom: Bool { pinnedToBottom }
        /// Called when the user picks "Revert to here" on a message (its id).
        var onRevert: ((String) -> Void)?
        /// Called when a message row is tapped — opens its detail screen.
        var onSelectMessage: ((String) -> Void)?

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            tableView.deselectRow(at: indexPath, animated: false)
            guard let id = dataSource?.itemIdentifier(for: indexPath) else { return }
            onSelectMessage?(id)
        }

        func makeDataSource(for table: UITableView) {
            dataSource = UITableViewDiffableDataSource<Int, String>(tableView: table) { [weak self] table, indexPath, id in
                let cell = table.dequeueReusableCell(withIdentifier: MessageCell.reuseID, for: indexPath) as! MessageCell
                if let entry = self?.rendered[id] { cell.configure(entry.message) }
                return cell
            }
        }

        func apply(_ messages: [MessageWithParts], table: UITableView) {
            guard let dataSource else { return }
            lookup = Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })

            let previous = Set(dataSource.snapshot().itemIdentifiers)
            var reconfigure: [String] = []
            for message in messages {
                let signature = Self.signature(message)
                if rendered[message.id]?.signature != signature {
                    rendered[message.id] = (signature, Self.render(message))
                    if previous.contains(message.id) { reconfigure.append(message.id) }
                }
            }
            // Drop caches for messages that no longer exist.
            let liveIDs = Set(messages.map(\.id))
            rendered = rendered.filter { liveIDs.contains($0.key) }
            heightCache = heightCache.filter { liveIDs.contains(String($0.key.prefix(while: { $0 != "|" }))) }

            var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
            snapshot.appendSections([0])
            snapshot.appendItems(messages.map(\.id), toSection: 0)
            if !reconfigure.isEmpty { snapshot.reconfigureItems(reconfigure) }
            dataSource.apply(snapshot, animatingDifferences: false)
        }

        // MARK: heights

        func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
            guard let id = dataSource?.itemIdentifier(for: indexPath), let entry = rendered[id] else {
                return UITableView.automaticDimension
            }
            let width = tableView.bounds.width
            let key = "\(id)|\(Int(width))|\(entry.signature)"
            if let cached = heightCache[key] { return cached }
            let height = MessageMetrics.height(for: entry.message, cellWidth: width)
            heightCache[key] = height
            return height
        }

        // MARK: context menu (copy)

        func tableView(_ tableView: UITableView,
                       contextMenuConfigurationForRowAt indexPath: IndexPath,
                       point: CGPoint) -> UIContextMenuConfiguration? {
            guard let id = dataSource?.itemIdentifier(for: indexPath) else { return nil }
            let text = rendered[id]?.message.plainText ?? ""
            let revert = onRevert
            guard !text.isEmpty || revert != nil else { return nil }
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
                var actions: [UIAction] = []
                if !text.isEmpty {
                    actions.append(UIAction(title: "Copy", image: UIImage(systemName: "doc.on.doc")) { _ in
                        UIPasteboard.general.string = text
                    })
                }
                if let revert {
                    actions.append(UIAction(title: "Revert to here",
                                            image: UIImage(systemName: "arrow.uturn.backward"),
                                            attributes: .destructive) { _ in revert(id) })
                }
                return UIMenu(children: actions)
            }
        }

        // MARK: scrolling + update scheduling

        /// Entry point from `updateUIView`. Buffers the update while the user is
        /// dragging so the list never jumps under their finger (#1).
        func receive(_ messages: [MessageWithParts], table: UITableView) {
            self.table = table
            if table.isTracking || table.isDragging || table.isDecelerating {
                pending = messages
                return
            }
            applyNow(messages, table: table)
        }

        private func applyNow(_ messages: [MessageWithParts], table: UITableView) {
            apply(messages, table: table)
            hasApplied = true
            if pinnedToBottom {
                table.layoutIfNeeded()
                scrollToBottom(table)
            }
        }

        private func flushPending() {
            guard let table, let messages = pending else { return }
            pending = nil
            applyNow(messages, table: table)
        }

        /// Called from `MessageTableView.layoutSubviews` — re-pins to the bottom
        /// when the frame changes (keyboard, rotation, first load), so the newest
        /// message stays visible and moves in sync with the keyboard (#5, #6).
        func tableDidLayout() {
            guard let table, pinnedToBottom,
                  !table.isTracking, !table.isDragging, !table.isDecelerating else { return }
            scrollToBottom(table)
        }

        func isNearBottom(_ table: UITableView, threshold: CGFloat = 140) -> Bool {
            guard hasApplied else { return true } // first load: pin to bottom
            let distance = table.contentSize.height - table.bounds.height - table.contentOffset.y
            return distance <= threshold
        }

        /// Idempotent — only moves if not already at the bottom, so it is safe to
        /// call from `layoutSubviews` without looping.
        func scrollToBottom(_ table: UITableView) {
            let target = table.contentSize.height - table.bounds.height + table.adjustedContentInset.bottom
            guard target > 0, abs(table.contentOffset.y - target) > 0.5 else { return }
            table.setContentOffset(CGPoint(x: 0, y: target), animated: false)
        }

        // MARK: scroll delegate — track pin state + flush deferred updates

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard let table = scrollView as? UITableView,
                  scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating else { return }
            pinnedToBottom = isNearBottom(table)
        }

        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            if let table = scrollView as? UITableView { pinnedToBottom = isNearBottom(table) }
            if !decelerate { flushPending() }
        }

        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
            if let table = scrollView as? UITableView { pinnedToBottom = isNearBottom(table) }
            flushPending()
        }

        // MARK: rendering

        private static let bodyFont = MessageMetrics.bodyFont

        private static func render(_ message: MessageWithParts) -> RenderedMessage {
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
                    blocks.append(.text(fileChip(file)))
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

        /// The model's reasoning: a small "THINKING" label followed by the thought
        /// in dimmed italic, so it reads as meta and recedes behind the real reply.
        /// Reasoning is collapsed to a one-line "Thinking" marker on the small
        /// phone screen — the full text is available by tapping into the message
        /// detail. Keeps long chains of thought from burying the actual answer.
        private static func reasoningBlocks(_ text: String) -> [MessageBlock] {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return [] }
            return [.text(NSAttributedString(string: "💭 Thinking", attributes: [
                .font: UIFont.systemFont(ofSize: bodyFont.pointSize - 1, weight: .medium),
                .foregroundColor: UIColor.tertiaryLabel]))]
        }

        /// Splits a text part into blocks: bounded markdown paragraphs (so no
        /// label grows huge), with any GFM table lifted out as a structured
        /// `.table` block rendered by `TableBlockView`.
        private static func textToBlocks(_ text: String) -> [MessageBlock] {
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

        /// Breaks a very long block on line boundaries so each piece stays under
        /// `maxChars`. Short blocks pass through unchanged.
        private static func boundedChunks(_ s: String, maxChars: Int) -> [String] {
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

        private static func toolLine(_ tool: ToolContent) -> NSAttributedString {
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
            // Never dump tool output inline — for `read` it's a whole file, for
            // `bash` a full build log. The web client keeps tool rows to one
            // line; full output belongs on a future tap-through detail screen.
            // Only an error is worth surfacing here.
            if tool.state.status == "error", let err = (tool.state.error ?? tool.state.output), !err.isEmpty {
                let snippet = err.count > 200 ? String(err.prefix(200)) + "…" : err
                line.append(NSAttributedString(string: "\n\(snippet)", attributes: [
                    .font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 2, weight: .regular),
                    .foregroundColor: UIColor.systemRed]))
            }
            return line
        }

        /// A `patch` part: "⌥ Patch  N files" (the committed change snapshot).
        private static func patchLine(_ patch: PatchContent) -> NSAttributedString {
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
        private static func fileChip(_ file: FileRefContent) -> NSAttributedString {
            NSAttributedString(string: "📎 \(FileRefDisplay.chip(file))", attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize - 1, weight: .regular),
                .foregroundColor: UIColor.systemTeal])
        }

        private static func tokenSummary(_ info: AssistantMessage) -> String {
            func fmt(_ n: Int) -> String { n >= 1000 ? "\(n / 1000)k" : "\(n)" }
            return "\(fmt(info.tokens.input))→\(fmt(info.tokens.output))"
        }

        /// A cheap fingerprint of a message's visible content — changes when
        /// streamed text grows, a tool's status/output changes, or it completes.
        private static func signature(_ message: MessageWithParts) -> Int {
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
}
