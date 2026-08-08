package studio.eugenezakharov.opencode

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.ProjectTime
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.api.models.SessionTime

/**
 * The rotation fix in [studio.eugenezakharov.opencode.ui.AppNav] persists the
 * selected Project/Session across an Activity recreation by JSON-encoding them
 * into the saved-instance-state Bundle (via a rememberSaveable Saver). That only
 * restores the user's place if the models round-trip losslessly — this guards it.
 */
class NavStateSaverTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun projectSurvivesJsonRoundTrip() {
        val project = Project(
            id = "prj_1",
            worktree = "/mnt/data/sources/sqlite_orm",
            vcs = "git",
            name = "sqlite_orm",
            time = ProjectTime(created = 1.0, updated = 2.0, initialized = 3.0),
            sandboxes = listOf("a", "b"),
        )
        val restored = json.decodeFromString<Project>(json.encodeToString(project))
        assertEquals(project, restored)
    }

    @Test fun sessionSurvivesJsonRoundTrip() {
        val session = Session(
            id = "ses_1",
            projectID = "prj_1",
            directory = "/mnt/data/sources/sqlite_orm",
            title = "Fix the parser",
            time = SessionTime(created = 1.0, updated = 2.0),
        )
        val restored = json.decodeFromString<Session>(json.encodeToString(session))
        assertEquals(session, restored)
    }
}
