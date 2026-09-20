package studio.eugenezakharov.opencode

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the "tap the text field and the whole chat jumps up" bug.
 *
 * Measured on a real device: without windowSoftInputMode the window PANNED up
 * (ADJUST_PAN) when the composer field focused — the content Box's top went to
 * y=-543 (off the top of the screen). With edge-to-edge (enableEdgeToEdge), the
 * IME must be delivered as insets, not a pan, so the activity MUST declare
 * adjustResize. Removing it silently brings the bug back, so pin it here.
 */
class ManifestSoftInputModeTest {
    @Test
    fun mainActivityUsesAdjustResize() {
        // Unit tests run with the module dir (…/app) as the working directory.
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
        assertTrue("AndroidManifest.xml not found from ${File(".").absolutePath}", manifest != null)

        val xml = manifest!!.readText()
        val activityBlock = xml.substringAfter(".MainActivity").substringBefore("</activity>")
        assertTrue(
            "MainActivity must declare android:windowSoftInputMode=\"adjustResize\" " +
                "or the window pans up on keyboard focus (edge-to-edge). Block was:\n$activityBlock",
            activityBlock.contains("android:windowSoftInputMode=\"adjustResize\""),
        )
    }
}
