package studio.eugenezakharov.opencode.ui.session

/**
 * Bottom inset the composer must apply, given the current IME and navigation-bar
 * insets (in px). It is the MAX of the two, never their sum.
 *
 * When the keyboard is up, the IME inset is measured from the bottom of the
 * screen and already spans the navigation-bar area. Adding the navigation-bar
 * inset on top of it (as `Modifier.navigationBarsPadding().imePadding()` did)
 * double-counts that strip, lifting the composer a navigation-bar height above
 * the keyboard and shoving the transcript up — the "tap the field and it jumps
 * up" bug. Taking the max keeps the composer flush on whichever is taller.
 */
object ComposerInsets {
    fun bottomInsetPx(imeBottomPx: Int, navBarBottomPx: Int): Int =
        maxOf(imeBottomPx, navBarBottomPx)
}
