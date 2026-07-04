package studio.eugenezakharov.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.ui.session.ShellStore

/**
 * The terminal history must persist across closing/reopening the dialog — it
 * lives in [ShellStore], not the composable's transient `remember` state.
 */
class ShellStoreTest {
    @Test fun historyPersistsAndClears() {
        val sid = "ses_persist"
        ShellStore.clear(sid)
        assertTrue(ShellStore.history(sid).isEmpty())

        ShellStore.history(sid).add("pwd" to "/tmp")
        // A fresh lookup returns the SAME list (survives, not recreated) — this is
        // what a reopened dialog sees.
        assertEquals(1, ShellStore.history(sid).size)
        assertEquals("pwd", ShellStore.history(sid)[0].first)

        ShellStore.clear(sid)
        assertTrue(ShellStore.history(sid).isEmpty())
    }

    @Test fun sessionsAreIsolated() {
        ShellStore.clear("a")
        ShellStore.clear("b")
        ShellStore.history("a").add("ls" to "out")
        assertTrue("other session's terminal is separate", ShellStore.history("b").isEmpty())
    }
}
