package studio.eugenezakharov.opencode

import androidx.compose.ui.graphics.Color
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.ui.session.CodeHighlighter

/**
 * `read` results are syntax-highlighted via the Highlights library: code text is
 * preserved, tokens get colored spans, and the "123: " gutter is kept + dimmed.
 * Parity with the iOS Highlightr path.
 */
class CodeHighlighterTest {
    @Test fun highlightsCppWithColoredSpans() {
        val code = "int main() { return 42; }"
        val a = CodeHighlighter.annotate(code, "a.cpp", dark = true)
        assertEquals("highlighting must not change the text", code, a.text)
        assertTrue("code should get colored spans", a.spanStyles.isNotEmpty())
        val colors = a.spanStyles.mapNotNull { it.item.color.takeIf { c -> c != Color.Unspecified } }.toSet()
        assertTrue("multiple token colors", colors.size > 1)
    }

    @Test fun readContentKeepsGutterAndCode() {
        val a = CodeHighlighter.readContent(
            "745: int x = 0;\n746: return x;", "a.cpp", dark = true, gutterColor = Color(0xFF888888),
        )
        assertTrue(a.text.contains("745:"))
        assertTrue(a.text.contains("746:"))
        assertTrue(a.text.contains("int x = 0;"))
        assertTrue(a.text.contains("return x;"))
    }

    @Test fun languageMapping() {
        assertEquals(SyntaxLanguage.CPP, CodeHighlighter.language("src/foo.cpp"))
        assertEquals(SyntaxLanguage.SWIFT, CodeHighlighter.language("a.swift"))
        assertEquals(SyntaxLanguage.PYTHON, CodeHighlighter.language("s.py"))
        assertEquals(SyntaxLanguage.DEFAULT, CodeHighlighter.language("Makefile"))
    }

    @Test fun languageMappingCoversEveryExtension() {
        // Every arm of the when(ext) — so a new extension can't silently fall to DEFAULT.
        val cases = mapOf(
            "h" to SyntaxLanguage.CPP, "c" to SyntaxLanguage.CPP, "ino" to SyntaxLanguage.CPP,
            "mjs" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT,
            "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT,
            "pyi" to SyntaxLanguage.PYTHON,
            "go" to SyntaxLanguage.GO, "rs" to SyntaxLanguage.RUST, "java" to SyntaxLanguage.JAVA,
            "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
            "rb" to SyntaxLanguage.RUBY, "php" to SyntaxLanguage.PHP, "cs" to SyntaxLanguage.CSHARP,
            "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL,
            "pl" to SyntaxLanguage.PERL, "dart" to SyntaxLanguage.DART,
        )
        for ((ext, lang) in cases) {
            assertEquals("ext .$ext", lang, CodeHighlighter.language("file.$ext"))
        }
        // Null filename and no extension both fall back to DEFAULT.
        assertEquals(SyntaxLanguage.DEFAULT, CodeHighlighter.language(null))
        assertEquals(SyntaxLanguage.DEFAULT, CodeHighlighter.language("README"))
    }

    @Test fun readContentWithoutGutterPassesThroughCode() {
        // The else branch of readContent: lines with no "123: " gutter.
        val a = CodeHighlighter.readContent(
            "fun main() {}\nval x = 1", "m.kt", dark = true, gutterColor = Color.Gray,
        )
        assertTrue(a.text.contains("fun main()"))
        assertTrue(a.text.contains("val x = 1"))
    }
}
