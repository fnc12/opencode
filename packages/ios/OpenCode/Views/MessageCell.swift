import UIKit

/// A fully pre-rendered message ready to lay out — built once per content change
/// by the list coordinator so neither layout nor height calculation re-parses
/// Markdown. The body is split into **blocks** (text paragraphs, tool lines,
/// tables), each in its own bounded view: text goes in a `UILabel`, a table in a
/// scrollable `TableBlockView`. This keeps any single text layer small (a long
/// message no longer becomes one giant, scroll-janking label).
struct RenderedMessage {
    let roleText: String
    let roleColor: UIColor
    let metaText: String?       // e.g. token summary, top-right
    let blocks: [MessageBlock]
    let bubbleColor: UIColor

    /// Plain text of the whole message, for copy-to-clipboard.
    var plainText: String {
        blocks.map { block in
            switch block {
            case .text(let s): return s.string
            case .table(let t): return ([t.header] + t.rows).map { $0.joined(separator: "\t") }.joined(separator: "\n")
            }
        }.joined(separator: "\n\n")
    }
}

/// Shared spacing constants and the single height formula used by both the cell
/// layout and `tableView(_:heightForRowAt:)`, so they can never disagree.
enum MessageMetrics {
    static let bodyFont = UIFont.preferredFont(forTextStyle: .body)
    static let outerH: CGFloat = 12   // bubble inset from the table edges
    static let outerV: CGFloat = 6    // vertical gap between bubbles
    static let padH: CGFloat = 14     // bubble inner horizontal padding
    static let padV: CGFloat = 10     // bubble inner vertical padding
    static let roleHeight: CGFloat = 18
    static let roleGap: CGFloat = 6   // role line → body
    static let blockGap: CGFloat = 8  // gap between body blocks

    static func bubbleWidth(cellWidth: CGFloat) -> CGFloat { cellWidth - outerH * 2 }
    static func textWidth(cellWidth: CGFloat) -> CGFloat { bubbleWidth(cellWidth: cellWidth) - padH * 2 }

    static func blockHeight(_ block: MessageBlock, cellWidth: CGFloat) -> CGFloat {
        switch block {
        case .text(let string):
            guard string.length > 0 else { return 0 }
            let bounds = string.boundingRect(
                with: CGSize(width: textWidth(cellWidth: cellWidth), height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
            return ceil(bounds.height)
        case .table(let table):
            return TableBlockView.height(for: table, font: bodyFont)
        }
    }

    static func bodyHeight(_ blocks: [MessageBlock], cellWidth: CGFloat) -> CGFloat {
        let heights = blocks.map { blockHeight($0, cellWidth: cellWidth) }.filter { $0 > 0 }
        guard !heights.isEmpty else { return 0 }
        return heights.reduce(0, +) + blockGap * CGFloat(heights.count - 1)
    }

    static func height(for rendered: RenderedMessage, cellWidth: CGFloat) -> CGFloat {
        let body = bodyHeight(rendered.blocks, cellWidth: cellWidth)
        let bubble = padV + roleHeight + roleGap + body + padV
        return outerV + bubble + outerV
    }
}

/// Hand-laid-out message cell — no Auto Layout, no SwiftUI hosting. The body is a
/// vertical stack of per-block views (labels for text, `TableBlockView` for
/// tables), rebuilt on bind. Frames are computed in `layoutSubviews`.
final class MessageCell: UITableViewCell {
    static let reuseID = "MessageCell"

    private let bubble = UIView()
    private let roleLabel = UILabel()
    private let metaLabel = UILabel()
    private var blockViews: [UIView] = []
    private var blocks: [MessageBlock] = []

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        selectionStyle = .none

        bubble.layer.cornerRadius = 12
        bubble.layer.cornerCurve = .continuous

        roleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        metaLabel.font = .systemFont(ofSize: 11)
        metaLabel.textColor = .secondaryLabel
        metaLabel.textAlignment = .right

        contentView.addSubview(bubble)
        bubble.addSubview(roleLabel)
        bubble.addSubview(metaLabel)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ rendered: RenderedMessage) {
        bubble.backgroundColor = rendered.bubbleColor
        roleLabel.text = rendered.roleText
        roleLabel.textColor = rendered.roleColor
        metaLabel.text = rendered.metaText
        metaLabel.isHidden = rendered.metaText == nil

        blockViews.forEach { $0.removeFromSuperview() }
        blocks = rendered.blocks.filter { block in
            if case .text(let s) = block { return s.length > 0 }
            return true
        }
        blockViews = blocks.map { block in
            switch block {
            case .text(let string):
                let label = UILabel()
                label.numberOfLines = 0
                label.lineBreakMode = .byWordWrapping
                label.attributedText = string
                bubble.addSubview(label)
                return label
            case .table(let table):
                let view = TableBlockView(table: table, font: MessageMetrics.bodyFont)
                bubble.addSubview(view)
                return view
            }
        }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        typealias m = MessageMetrics
        let cellWidth = contentView.bounds.width
        let bubbleWidth = m.bubbleWidth(cellWidth: cellWidth)
        let textWidth = m.textWidth(cellWidth: cellWidth)

        let metaWidth: CGFloat = 90
        roleLabel.frame = CGRect(x: m.padH, y: m.padV, width: bubbleWidth - m.padH * 2 - metaWidth, height: m.roleHeight)
        metaLabel.frame = CGRect(x: bubbleWidth - m.padH - metaWidth, y: m.padV, width: metaWidth, height: m.roleHeight)

        var y = m.padV + m.roleHeight + m.roleGap
        for (i, block) in blocks.enumerated() {
            let h = m.blockHeight(block, cellWidth: cellWidth)
            blockViews[i].frame = CGRect(x: m.padH, y: y, width: textWidth, height: h)
            y += h + m.blockGap
        }
        let bodyBottom = blocks.isEmpty ? (m.padV + m.roleHeight + m.roleGap) : (y - m.blockGap)
        bubble.frame = CGRect(x: m.outerH, y: m.outerV, width: bubbleWidth, height: bodyBottom + m.padV)
    }
}
