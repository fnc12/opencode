package studio.eugenezakharov.opencode

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.ui.screens.ProjectListAdapter

/**
 * Paparazzi golden for the project list row rendered by the REAL
 * [ProjectListAdapter] (View-based RecyclerView, was 0%). Uses the public adapter
 * API (submit + onCreate/onBindViewHolder) so the bind path (name, shortened
 * path) executes. Deterministic — the row shows no relative time.
 */
class ProjectListAdapterSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT),
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(project: Project): View {
        val adapter = ProjectListAdapter(
            primaryColor = Color.WHITE, secondaryColor = Color.GRAY, onClick = {},
        )
        adapter.submit(listOf(project))
        val holder = adapter.onCreateViewHolder(FrameLayout(paparazzi.context), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    @Test fun projectRow() {
        val project = json.decodeFromString(
            Project.serializer(),
            """{"id":"p1","worktree":"/Users/me/sources/sqlite_orm","name":"sqlite_orm",
                "time":{"created":1.0,"updated":2.0},"sandboxes":[]}""",
        )
        paparazzi.snapshot(row(project))
    }
}
