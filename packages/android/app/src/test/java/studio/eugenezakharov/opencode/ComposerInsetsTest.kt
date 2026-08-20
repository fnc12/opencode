package studio.eugenezakharov.opencode

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.eugenezakharov.opencode.ui.session.ComposerInsets

/**
 * The composer's bottom inset must be the MAX of the IME and navigation-bar
 * insets, not their sum. Summing double-counts the navigation-bar strip when the
 * keyboard is up (the IME inset already covers it), which lifted the composer a
 * nav-bar height above the keyboard and pushed the transcript up — the reported
 * "tap the text field and it jumps up" regression.
 */
class ComposerInsetsTest {
    @Test
    fun keyboardUp_ignoresNavBarThatImeAlreadyCovers() {
        // IME 800px over a 132px nav bar → 800, NOT 932.
        assertEquals(800, ComposerInsets.bottomInsetPx(imeBottomPx = 800, navBarBottomPx = 132))
    }

    @Test
    fun keyboardDown_usesNavBarOnly() {
        assertEquals(132, ComposerInsets.bottomInsetPx(imeBottomPx = 0, navBarBottomPx = 132))
    }

    @Test
    fun noInsets_isZero() {
        assertEquals(0, ComposerInsets.bottomInsetPx(imeBottomPx = 0, navBarBottomPx = 0))
    }

    @Test
    fun imeTallerThanNav_usesIme() {
        assertEquals(1000, ComposerInsets.bottomInsetPx(imeBottomPx = 1000, navBarBottomPx = 48))
    }
}
