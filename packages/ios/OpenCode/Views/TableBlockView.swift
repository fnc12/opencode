import UIKit

/// Renders a markdown table as a real grid inside a horizontal `UIScrollView`,
/// so wide tables scroll sideways instead of wrapping (mirroring the web client).
/// Rows are fixed-height single-line cells; columns size to their content.
final class TableBlockView: UIView {
    private let scroll = UIScrollView()
    private let table: MessageTable
    private let font: UIFont

    private static let padH: CGFloat = 10
    private static let padV: CGFloat = 6
    private static let maxColWidth: CGFloat = 280

    init(table: MessageTable, font: UIFont) {
        self.table = table
        self.font = font
        super.init(frame: .zero)
        scroll.showsHorizontalScrollIndicator = true
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceVertical = false
        layer.cornerRadius = 8
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor.separator.cgColor
        clipsToBounds = true
        addSubview(scroll)
        buildGrid()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    static func rowHeight(font: UIFont) -> CGFloat { ceil(font.lineHeight) + padV * 2 }

    /// The table's fixed height (single-line rows), used for row-height calc.
    static func height(for table: MessageTable, font: UIFont) -> CGFloat {
        rowHeight(font: font) * CGFloat(1 + table.rows.count)
    }

    /// Cell content with inline markdown (bold / italic / `code`) resolved.
    private func cellText(_ s: String, header: Bool) -> NSAttributedString {
        let f = header ? UIFont.boldSystemFont(ofSize: font.pointSize) : font
        return MarkdownRenderer.inlineAttributed(s, font: f, color: .label)
    }

    private func columnWidths() -> [CGFloat] {
        let n = table.columnCount
        var widths = [CGFloat](repeating: 0, count: n)
        for c in 0..<n {
            var w: CGFloat = c < table.header.count ? ceil(cellText(table.header[c], header: true).size().width) : 0
            for row in table.rows where c < row.count { w = max(w, ceil(cellText(row[c], header: false).size().width)) }
            widths[c] = min(Self.maxColWidth, w) + Self.padH * 2
        }
        return widths
    }

    private func buildGrid() {
        let widths = columnWidths()
        let rowH = Self.rowHeight(font: font)
        let allRows = [table.header] + table.rows
        let contentW = widths.reduce(0, +)
        let contentH = rowH * CGFloat(allRows.count)

        let headerBG = UIView(frame: CGRect(x: 0, y: 0, width: contentW, height: rowH))
        headerBG.backgroundColor = UIColor.secondaryLabel.withAlphaComponent(0.12)
        scroll.addSubview(headerBG)

        for r in 1..<allRows.count {
            let sep = UIView(frame: CGRect(x: 0, y: CGFloat(r) * rowH, width: contentW, height: 0.5))
            sep.backgroundColor = UIColor.separator
            scroll.addSubview(sep)
        }

        var x: CGFloat = 0
        for c in 0..<widths.count {
            for (r, row) in allRows.enumerated() {
                let cell = UILabel(frame: CGRect(x: x + Self.padH, y: CGFloat(r) * rowH,
                                                 width: widths[c] - Self.padH * 2, height: rowH))
                cell.lineBreakMode = .byTruncatingTail
                cell.attributedText = cellText(c < row.count ? row[c] : "", header: r == 0)
                scroll.addSubview(cell)
            }
            x += widths[c]
            if c < widths.count - 1 {
                let vsep = UIView(frame: CGRect(x: x - 0.5, y: 0, width: 0.5, height: contentH))
                vsep.backgroundColor = UIColor.separator
                scroll.addSubview(vsep)
            }
        }
        scroll.contentSize = CGSize(width: contentW, height: contentH)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        scroll.frame = bounds
    }
}
