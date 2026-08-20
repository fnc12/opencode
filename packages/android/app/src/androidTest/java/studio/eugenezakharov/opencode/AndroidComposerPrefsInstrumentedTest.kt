package studio.eugenezakharov.opencode

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.AndroidComposerPrefs

/**
 * Instrumented coverage for the production SharedPreferences-backed ComposerPrefs
 * (Context-bound, so it can't run on the JVM). Verifies the getters/setters and
 * setModel round-trip through real SharedPreferences.
 */
@RunWith(AndroidJUnit4::class)
class AndroidComposerPrefsInstrumentedTest {
    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun clear() {
        ctx().getSharedPreferences("opencode_composer", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun defaultsThenPersistsProviderModelAgent() {
        val prefs = AndroidComposerPrefs(ctx())
        // Defaults: empty provider/model, "build" agent.
        assertEquals("", prefs.providerID)
        assertEquals("", prefs.modelID)
        assertEquals("build", prefs.agent)

        prefs.providerID = "zai"
        prefs.modelID = "glm-5.2"
        prefs.agent = "plan"

        // A fresh instance reads back the persisted values.
        val reloaded = AndroidComposerPrefs(ctx())
        assertEquals("zai", reloaded.providerID)
        assertEquals("glm-5.2", reloaded.modelID)
        assertEquals("plan", reloaded.agent)
    }

    @Test fun setModelWritesBothKeys() {
        val prefs = AndroidComposerPrefs(ctx())
        prefs.setModel("openai", "gpt-5")
        val reloaded = AndroidComposerPrefs(ctx())
        assertEquals("openai", reloaded.providerID)
        assertEquals("gpt-5", reloaded.modelID)
    }
}
