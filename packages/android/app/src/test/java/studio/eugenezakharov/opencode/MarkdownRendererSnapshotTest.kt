package studio.eugenezakharov.opencode

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.ui.session.MarkdownRenderer

/**
 * Screenshot (Paparazzi) coverage for [MarkdownRenderer]. It produces a `Spanned`
 * (an android.text framework type) that can't be asserted on plain JVM, so it was
 * 0%-covered — a screenshot test both executes every branch AND pins the visual
 * result. Rendered into a TextView on the JVM (no emulator).
 *
 * Record/update goldens: `./gradlew :app:recordPaparazziDebug`
 * Verify (CI/local runner): `./gradlew :app:verifyPaparazziDebug`
 */
class MarkdownRendererSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        // SHRINK crops the golden to the view's own bounds instead of the full
        // device screen, so the diff is just the content.
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private fun textView(markdown: String): TextView =
        TextView(paparazzi.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 15f
            setPadding(24, 24, 24, 24)
            text = MarkdownRenderer.render(markdown, baseSizePx = 15, textColor = Color.BLACK)
        }

    @Test fun headings() {
        paparazzi.snapshot(textView("# H1 heading\n## H2 heading\n### H3 heading\nbody text"))
    }

    @Test fun inlineFormatting() {
        paparazzi.snapshot(textView("**bold**, *italic*, and `inline code` in a line."))
    }

    @Test fun listsAndLinks() {
        paparazzi.snapshot(textView("- first item\n- second item\n\nSee [the docs](https://x) here."))
    }

    @Test fun table() {
        paparazzi.snapshot(
            textView("| Col A | Col B |\n|---|---|\n| a1 | b1 |\n| a2 | b2 |"),
        )
    }
}
