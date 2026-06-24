import UIKit

/// A fully pre-rendered message ready to lay out — built once per content change
/// by the list coordinator so neither layout nor height calculation re-parses
/// Markdown.
struct RenderedMessage {
    let roleText: String
    let roleColor: UIColor
    let metaText: String?       // e.g. token summary, top-right
    let body: NSAttributedString
    let bubbleColor: UIColor
}

/// Shared spacing constants and the single height formula used by both the cell
/// layout and `tableView(_:heightForRowAt:)`, so they can never disagree.
enum MessageMetrics {
    static let outerH: CGFloat = 12   // bubble inset from the table edges
    static let outerV: CGFloat = 6    // vertical gap between bubbles
    static let padH: CGFloat = 14     // bubble inner horizontal padding
    static let padV: CGFloat = 10     // bubble inner vertical padding
    static let roleHeight: CGFloat = 18
    static let roleGap: CGFloat = 6   // role line → body

    static func bubbleWidth(cellWidth: CGFloat) -> CGFloat { cellWidth - outerH * 2 }
    static func textWidth(cellWidth: CGFloat) -> CGFloat { bubbleWidth(cellWidth: cellWidth) - padH * 2 }

    static func bodyHeight(_ body: NSAttributedString, cellWidth: CGFloat) -> CGFloat {
        guard body.length > 0 else { return 0 }
        let bounds = body.boundingRect(
            with: CGSize(width: textWidth(cellWidth: cellWidth), height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil)
        return ceil(bounds.height)
    }

    static func height(for rendered: RenderedMessage, cellWidth: CGFloat) -> CGFloat {
        let body = bodyHeight(rendered.body, cellWidth: cellWidth)
        let bubble = padV + roleHeight + roleGap + body + padV
        return outerV + bubble + outerV
    }
}

/// Hand-laid-out message cell — no Auto Layout, no SwiftUI hosting. Frames are
/// computed in `layoutSubviews` with the same metrics the table uses for height.
final class MessageCell: UITableViewCell {
    static let reuseID = "MessageCell"

    private let bubble = UIView()
    private let roleLabel = UILabel()
    private let metaLabel = UILabel()
    private let bodyLabel = UILabel()

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
        bodyLabel.numberOfLines = 0
        bodyLabel.lineBreakMode = .byWordWrapping

        contentView.addSubview(bubble)
        bubble.addSubview(roleLabel)
        bubble.addSubview(metaLabel)
        bubble.addSubview(bodyLabel)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ rendered: RenderedMessage) {
        bubble.backgroundColor = rendered.bubbleColor
        roleLabel.text = rendered.roleText
        roleLabel.textColor = rendered.roleColor
        metaLabel.text = rendered.metaText
        metaLabel.isHidden = rendered.metaText == nil
        bodyLabel.attributedText = rendered.body
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        typealias m = MessageMetrics
        let cellWidth = contentView.bounds.width
        let bubbleWidth = m.bubbleWidth(cellWidth: cellWidth)
        let textWidth = m.textWidth(cellWidth: cellWidth)
        let bodyHeight = m.bodyHeight(bodyLabel.attributedText ?? NSAttributedString(), cellWidth: cellWidth)
        let bubbleHeight = m.padV + m.roleHeight + m.roleGap + bodyHeight + m.padV

        bubble.frame = CGRect(x: m.outerH, y: m.outerV, width: bubbleWidth, height: bubbleHeight)

        let metaWidth: CGFloat = 90
        roleLabel.frame = CGRect(x: m.padH, y: m.padV, width: bubbleWidth - m.padH * 2 - metaWidth, height: m.roleHeight)
        metaLabel.frame = CGRect(x: bubbleWidth - m.padH - metaWidth, y: m.padV, width: metaWidth, height: m.roleHeight)
        bodyLabel.frame = CGRect(x: m.padH, y: m.padV + m.roleHeight + m.roleGap, width: textWidth, height: bodyHeight)
    }
}
