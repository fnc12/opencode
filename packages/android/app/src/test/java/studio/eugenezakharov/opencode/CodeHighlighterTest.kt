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
}
