package studio.eugenezakharov.opencode

import android.app.Application
import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import studio.eugenezakharov.opencode.api.ConnectionConfig
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.api.ConnectionStore
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.ui.screens.SessionListAdapter

/**
 * Instrumented coverage for the Context-bound ConnectionStore (real
 * EncryptedSharedPreferences) and the SessionListAdapter row rendering (a
 * RecyclerView ViewHolder), both 0% on the JVM side.
 */
@RunWith(AndroidJUnit4::class)
class StoresAndAdaptersInstrumentedTest {
    private fun ctx() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun connectionStoreRoundTrip() {
        val store = ConnectionStore(ctx())
        store.clear()
        assertNull("cleared store loads nothing", store.load())

        val cfg = ConnectionConfig(mode = ConnectionMode.RELAY, relayURL = "https://r", tunnelID = "t", token = "k")
        store.save(cfg)
        val loaded = store.load()
        assertEquals(ConnectionMode.RELAY, loaded?.mode)
        assertEquals("t", loaded?.tunnelID)

        store.clear()
        assertNull(store.load())
    }

    @Test fun sessionRowBinds() {
        val session = Json { ignoreUnknownKeys = true }.decodeFromString(
            Session.serializer(),
            """{"id":"s1","projectID":"p1","directory":"/w","title":"Fix the parser","version":"1",
                "time":{"created":1.0,"updated":2.0}}""",
        )
        val adapter = SessionListAdapter(
            primaryColor = Color.WHITE, secondaryColor = Color.GRAY,
            addColor = Color.GREEN, delColor = Color.RED,
            onClick = {}, onRename = {}, onDelete = {},
        )
        adapter.submit(listOf(session))
        val parent = FrameLayout(ctx())
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        // The row bound without throwing; the adapter reports one item.
        assertEquals(1, adapter.itemCount)
    }

    @Test fun busySessionShowsWorking() {
        val session = Json { ignoreUnknownKeys = true }.decodeFromString(
            Session.serializer(),
            """{"id":"s1","projectID":"p1","directory":"/w","title":"T","version":"1",
                "time":{"created":1.0,"updated":2.0}}""",
        )
        val adapter = SessionListAdapter(
            primaryColor = Color.WHITE, secondaryColor = Color.GRAY,
            addColor = Color.GREEN, delColor = Color.RED,
            onClick = {}, onRename = {}, onDelete = {},
        )
        adapter.submit(listOf(session), busyIds = setOf("s1"))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx()), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(1, adapter.itemCount)
    }
}
