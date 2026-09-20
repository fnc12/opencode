package studio.eugenezakharov.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.SessionStore
import studio.eugenezakharov.opencode.ui.AppUiState
import studio.eugenezakharov.opencode.ui.session.SessionUiState

/**
 * The two UI state holders are constructed with all-default values on first
 * render (before any load); assert those defaults so the primary constructors +
 * default initializers are exercised outside the live ViewModels.
 */
class UiStateDefaultsTest {
    @Test fun sessionUiStateDefaults() {
        val s = SessionUiState()
        assertTrue(s.messages.isEmpty())
        assertEquals(0, s.revision)
        assertEquals(SessionStore.StreamStatus.IDLE, s.status)
        assertFalse(s.isBusy)
        assertTrue("loads on first render", s.loading)
        assertNull(s.error)
        assertEquals("", s.providerID)
        assertEquals("build", s.agentName)
        assertFalse(s.sending)
        assertTrue(s.pendingPermissions.isEmpty())
        assertTrue(s.pendingQuestions.isEmpty())
        assertFalse(s.loadingOlder)
        assertFalse(s.hasDiff)
        assertNull(s.revertMessageID)
    }

    @Test fun appUiStateDefaults() {
        val a = AppUiState()
        assertFalse(a.connected)
        assertEquals("", a.version)
        assertFalse(a.loading)
        assertNull(a.error)
    }
}
