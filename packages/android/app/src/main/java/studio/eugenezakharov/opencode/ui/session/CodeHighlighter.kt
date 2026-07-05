package studio.eugenezakharov.opencode.ui.session

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Syntax-highlights code into an [AnnotatedString] via the Highlights library
 * (Kotlin-native, any language), so `read` results render like the web client.
 * The result goes into a selectable Text, so selection is preserved.
 */
object CodeHighlighter {
    fun annotate(code: String, filename: String?, dark: Boolean): AnnotatedString {
        val marks = Highlights.Builder()
            .code(code)
            .language(language(filename))
            .theme(SyntaxThemes.default(darkMode = dark))
            .build()
            .getHighlights()
        return buildAnnotatedString {
            append(code)
            marks.filterIsInstance<ColorHighlight>().forEach {
                addStyle(
                    SpanStyle(color = Color(0xFF000000.toInt() or it.rgb)),
                    it.location.start.coerceIn(0, code.length),
                    it.location.end.coerceIn(0, code.length),
                )
            }
            marks.filterIsInstance<BoldHighlight>().forEach {
                addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    it.location.start.coerceIn(0, code.length),
                    it.location.end.coerceIn(0, code.length),
                )
            }
        }
    }

    private val GUTTER = Regex("^\\s*\\d+:\\s?")

    /** A `read` file result: strip the "123: " line-number gutter, highlight the
     *  code, then re-attach the numbers dimmed — mirroring the web's gutter. */
    fun readContent(content: String, filename: String?, dark: Boolean, gutterColor: Color): AnnotatedString {
        val gutters = ArrayList<String>()
        val codeLines = ArrayList<String>()
        content.split("\n").forEach { line ->
            val m = GUTTER.find(line)
            if (m != null) {
                gutters.add(m.value)
                codeLines.add(line.substring(m.value.length))
            } else {
                gutters.add("")
                codeLines.add(line)
            }
        }
        val highlighted = annotate(codeLines.joinToString("\n"), filename, dark)
        return buildAnnotatedString {
            var offset = 0
            codeLines.forEachIndexed { i, cl ->
                if (i > 0) append("\n")
                if (gutters[i].isNotEmpty()) {
                    withStyle(SpanStyle(color = gutterColor)) { append(gutters[i]) }
                }
                append(highlighted.subSequence(offset, (offset + cl.length).coerceAtMost(highlighted.length)))
                offset += cl.length + 1
            }
        }
    }

    /** Map a filename to a Highlights language, falling back to DEFAULT. */
    fun language(filename: String?): SyntaxLanguage {
        val ext = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "cpp", "cc", "cxx", "hpp", "hxx", "h", "c", "ino" -> SyntaxLanguage.CPP
            "swift" -> SyntaxLanguage.SWIFT
            "js", "mjs", "cjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
            "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
            "py", "pyi" -> SyntaxLanguage.PYTHON
            "go" -> SyntaxLanguage.GO
            "rs" -> SyntaxLanguage.RUST
            "java" -> SyntaxLanguage.JAVA
            "kt", "kts" -> SyntaxLanguage.KOTLIN
            "rb" -> SyntaxLanguage.RUBY
            "php" -> SyntaxLanguage.PHP
            "cs" -> SyntaxLanguage.CSHARP
            "sh", "bash", "zsh" -> SyntaxLanguage.SHELL
            "pl" -> SyntaxLanguage.PERL
            "dart" -> SyntaxLanguage.DART
            else -> SyntaxLanguage.DEFAULT
        }
    }
}
