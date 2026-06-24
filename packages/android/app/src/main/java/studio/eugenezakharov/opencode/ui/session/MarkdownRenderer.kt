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
        for ((index, line) in lines.withIndex()) {
            if (index > 0) out.append("\n")
            val header = parseHeader(line)
            if (header != null) {
                val start = out.length
                appendInline(out, header.second, textColor)
                val size = headerSize(header.first, baseSizePx)
                out.setSpan(AbsoluteSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                appendInline(out, line, textColor)
            }
        }
        return out
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
