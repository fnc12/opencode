import SwiftUI
import UIKit

/// The conversation list as a hand-tuned `UITableView` — custom cells, manually
/// computed + cached row heights, no SwiftUI hosting in cells and no bindings.
/// Markdown is rendered to `NSAttributedString` once per content change; while a
/// message streams we `reconfigureItems` only that one row and recompute only
/// its height.
struct MessageListView: UIViewRepresentable {
    let messages: [MessageWithParts]
    /// Bumped by the store on every change; ties SwiftUI's `updateUIView` to data updates.
    let revision: Int

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UITableView {
        let table = UITableView(frame: .zero, style: .plain)
        table.separatorStyle = .none
        table.backgroundColor = .clear
        table.allowsSelection = false
        table.keyboardDismissMode = .interactive
        table.estimatedRowHeight = 120
        table.register(MessageCell.self, forCellReuseIdentifier: MessageCell.reuseID)
        table.contentInset = UIEdgeInsets(top: 6, left: 0, bottom: 6, right: 0)
        table.delegate = context.coordinator
        context.coordinator.makeDataSource(for: table)
        return table
    }

    func updateUIView(_ table: UITableView, context: Context) {
        let coordinator = context.coordinator
        let stickToBottom = coordinator.isNearBottom(table)
        coordinator.apply(messages, table: table)
        coordinator.hasApplied = true
        if stickToBottom {
            table.layoutIfNeeded()
            coordinator.scrollToBottom(table)
        }
    }

    @MainActor
    final class Coordinator: NSObject, UITableViewDelegate {
        private var dataSource: UITableViewDiffableDataSource<Int, String>?
        private var lookup: [String: MessageWithParts] = [:]
        private var rendered: [String: (signature: Int, message: RenderedMessage)] = [:]
        private var heightCache: [String: CGFloat] = [:]
        var hasApplied = false

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

        // MARK: scrolling

        func isNearBottom(_ table: UITableView, threshold: CGFloat = 140) -> Bool {
            guard hasApplied else { return true } // first load: pin to bottom
            let distance = table.contentSize.height - table.bounds.height - table.contentOffset.y
            return distance <= threshold
        }

        func scrollToBottom(_ table: UITableView) {
            let bottom = table.contentSize.height - table.bounds.height + table.adjustedContentInset.bottom
            guard bottom > 0 else { return }
            table.setContentOffset(CGPoint(x: 0, y: bottom), animated: false)
        }

        // MARK: rendering

        private static let bodyFont = UIFont.preferredFont(forTextStyle: .body)

        private static func render(_ message: MessageWithParts) -> RenderedMessage {
            let body = NSMutableAttributedString()
            func spacer() { if body.length > 0 { body.append(NSAttributedString(string: "\n\n")) } }

            for part in message.parts {
                switch part.content {
                case .text(let text) where !text.isEmpty:
                    spacer(); body.append(MarkdownRenderer.attributed(text, font: bodyFont, color: .label))
                case .tool(let tool):
                    spacer(); body.append(toolLine(tool))
                case .patch(let patch):
                    spacer(); body.append(patchLine(patch))
                case .file(let file):
                    spacer(); body.append(fileChip(file))
                case .stepStart(let step):
                    if let title = step.title {
                        spacer()
                        body.append(NSAttributedString(string: title, attributes: [
                            .font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.secondaryLabel]))
                    }
                case .text, .stepFinish, nil:
                    break
                }
            }

            switch message.info {
            case .user:
                return RenderedMessage(
                    roleText: "You", roleColor: .systemBlue, metaText: nil,
                    body: body, bubbleColor: UIColor.systemBlue.withAlphaComponent(0.12))
            case .assistant(let info):
                if let error = info.error {
                    spacer()
                    body.append(NSAttributedString(string: "Error: \(error.displayText)", attributes: [
                        .font: bodyFont, .foregroundColor: UIColor.systemOrange]))
                }
                return RenderedMessage(
                    roleText: info.agent, roleColor: .systemGreen, metaText: tokenSummary(info),
                    body: body, bubbleColor: UIColor.white.withAlphaComponent(0.06))
            }
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
                case .tool(let tool): hasher.combine(tool.state.status); hasher.combine(tool.state.title); hasher.combine(tool.tool)
                case .patch(let patch): hasher.combine(patch.files.count); hasher.combine(patch.hash)
                case .file(let file): hasher.combine(file.filename); hasher.combine(file.url)
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
