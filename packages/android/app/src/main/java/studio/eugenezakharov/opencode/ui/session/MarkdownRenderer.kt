package studio.eugenezakharov.opencode.ui.session

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Converts a small subset of Markdown into a [Spanned] with concrete styling:
 * bold (`**`), italic (`*` / `_`), inline `code`, and ATX headers (`#`…`######`).
 * Block constructs beyond headers render as inline text. Hand-rolled (no deps),
 * mirroring the iOS `MarkdownRenderer` decision.
 */
object MarkdownRenderer {
    private const val CODE_BG = 0x33808080 // translucent gray

    fun render(markdown: String, baseSizePx: Int, textColor: Int): Spanned {
        val out = SpannableStringBuilder()
        val lines = markdown.split("\n")
        var i = 0
        var first = true
        while (i < lines.size) {
            // GFM table: a row of `|` cells whose next line is a `---|---` separator.
            if (i + 1 < lines.size && isTableRow(lines[i]) && isSeparator(lines[i + 1])) {
                var j = i
                val tableLines = ArrayList<String>()
                while (j < lines.size && isTableRow(lines[j])) { tableLines.add(lines[j]); j++ }
                if (!first) out.append("\n")
                appendTable(out, tableLines, baseSizePx, textColor)
                first = false
                i = j
                continue
            }
            if (!first) out.append("\n")
            first = false
            val header = parseHeader(lines[i])
            if (header != null) {
                val start = out.length
                appendInline(out, header.second, textColor)
                val size = headerSize(header.first, baseSizePx)
                out.setSpan(AbsoluteSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                appendInline(out, lines[i], textColor)
            }
            i++
        }
        return out
    }

    private fun isTableRow(line: String) = line.contains('|') && line.isNotBlank()

    /** A `|---|:--:|` alignment/separator row. */
    private fun isSeparator(line: String): Boolean {
        val cells = splitCells(line)
        return cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' } }
    }

    private fun splitCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }

    /**
     * Renders a GFM table as an aligned monospace grid: a bold header row, a rule,
     * then data rows with `│` column separators. Cell inline markdown (bold/code)
     * is rendered; columns are padded to the widest rendered cell so they line up.
     * (Very wide tables can still wrap on a narrow screen — a monospace grid is the
     * pragmatic fit for the single-TextView message body.)
     */
    private fun appendTable(out: SpannableStringBuilder, lines: List<String>, baseSizePx: Int, textColor: Int) {
        val dataLines = lines.filterNot { isSeparator(it) }
        if (dataLines.isEmpty()) return
        val grid = dataLines.map { splitCells(it) }
        val ncol = grid.maxOf { it.size }
        // Pre-render each cell so we can measure its true (rendered) width.
        val cells: List<List<SpannableStringBuilder>> = grid.map { row ->
            (0 until ncol).map { c ->
                SpannableStringBuilder().also { appendInline(it, row.getOrElse(c) { "" }, textColor) }
            }
        }
        val widths = IntArray(ncol) { c -> cells.maxOf { it[c].length } }
        val tableStart = out.length
        for ((r, row) in cells.withIndex()) {
            val rowStart = out.length
            for (c in 0 until ncol) {
                out.append(row[c])
                val pad = widths[c] - row[c].length
                if (pad > 0) out.append(" ".repeat(pad))
                if (c < ncol - 1) out.append(" │ ")
            }
            if (r == 0) {
                out.setSpan(StyleSpan(Typeface.BOLD), rowStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append("\n")
                out.append("─".repeat(widths.sum() + (ncol - 1) * 3))
            }
            if (r < cells.size - 1) out.append("\n")
        }
        // Monospace across the whole grid so the padding aligns; smaller so more fits.
        out.setSpan(TypefaceSpan("monospace"), tableStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(AbsoluteSizeSpan((baseSizePx * 0.85f).toInt()), tableStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun parseHeader(line: String): Pair<Int, String>? {
        val trimmed = line.trimStart(' ')
        var level = 0
        var i = 0
        while (i < trimmed.length && trimmed[i] == '#') { level++; i++ }
        if (level !in 1..6 || i >= trimmed.length || trimmed[i] != ' ') return null
        return level to trimmed.substring(i).trimStart(' ')
    }

    private fun headerSize(level: Int, base: Int): Int = when (level) {
        1 -> base + 14
        2 -> base + 8
        3 -> base + 4
        else -> base + 2
    }

    /**
     * Appends one line, applying inline spans. Scans for `**bold**`, `*italic*`
     * / `_italic_`, and `` `code` `` runs; everything else is literal text.
     */
    private fun appendInline(out: SpannableStringBuilder, line: String, textColor: Int) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(line.substring(i + 1, end))
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        out.append(c); i++
                    }
                }
                c == '*' && i + 1 < line.length && line[i + 1] == '*' -> {
                    val end = line.indexOf("**", i + 2)
                    if (end > i) {
                        val start = out.length
                        out.append(line.substring(i + 2, end))
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        out.append(c); i++
                    }
                }
                (c == '*' || c == '_') -> {
                    val end = line.indexOf(c, i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(line.substring(i + 1, end))
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        out.append(c); i++
                    }
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
        // Apply base text color across the whole appended line region is handled by the TextView color;
        // we only override for code/link in future. Keep textColor param for parity / explicit spans.
        if (textColor != Color.TRANSPARENT) {
            // no-op: base color comes from the TextView; spans above don't change foreground.
        }
    }
}
