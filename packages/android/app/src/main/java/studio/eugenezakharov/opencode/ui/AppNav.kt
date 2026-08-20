package studio.eugenezakharov.opencode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import studio.eugenezakharov.opencode.api.ServerConnection
import studio.eugenezakharov.opencode.api.models.Project
import studio.eugenezakharov.opencode.api.models.Session
import studio.eugenezakharov.opencode.ui.screens.ConnectScreen
import studio.eugenezakharov.opencode.ui.screens.ProjectListScreen
import studio.eugenezakharov.opencode.ui.screens.SessionListScreen
import studio.eugenezakharov.opencode.ui.session.SessionScreen
import studio.eugenezakharov.opencode.ui.session.SessionViewModel

/**
 * Root navigation. Mirrors the iOS flow: not connected → Connect; connected →
 * Projects → Sessions → live Session. Selected project/session are held in
 * local state (they carry rich objects, simpler than serializing through routes).
 */
@Composable
fun AppNav(appViewModel: AppViewModel = viewModel()) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val busySessions by appViewModel.busySessions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { appViewModel.autoConnectIfPossible() }

    // rememberSaveable (not plain remember) so the selected project/session
    // survive an Activity recreation — screen rotation used to reset them to null,
    // dumping the user back on the Projects screen mid-navigation.
    var selectedProject by rememberSaveable(stateSaver = jsonNavSaver<Project>()) {
        mutableStateOf<Project?>(null)
    }
    var selectedSession by rememberSaveable(stateSaver = jsonNavSaver<Session>()) {
        mutableStateOf<Session?>(null)
    }

    // Deep-link from a tapped push: resolve the session by id (the push carries
    // only the id) and navigate to it, selecting its project so Back walks
    // chat → session list → projects.
    val pendingOpen by appViewModel.pendingOpenSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen, state.connected) {
        val sid = pendingOpen
        if (sid == null || !state.connected) return@LaunchedEffect
        val session = runCatching { appViewModel.server.getSession(sid) }.getOrNull()
        if (session != null) {
            selectedProject = runCatching { appViewModel.server.projects() }
                .getOrNull()?.firstOrNull { it.id == session.projectID }
            selectedSession = session
        }
        // Clear LAST: pendingOpen is an effect key, so clearing it cancels this
        // coroutine — do it only after both network calls have finished.
        appViewModel.clearPendingOpenSession()
    }

    when {
        !state.connected -> {
            selectedProject = null
            selectedSession = null
            ConnectScreen(state = state, viewModel = appViewModel)
        }
        selectedSession != null -> {
            // The back *gesture* (not just the toolbar arrow) must walk the same
            // path: chat → session list / projects. Without this it fell through
            // to the Activity and exited the app, eventually cold-starting onto
            // the Connect screen.
            BackHandler { selectedSession = null }
            SessionDestination(
                server = appViewModel.server,
                session = selectedSession!!,
                // Back returns to the session list if we came through a project,
                // otherwise straight to the projects screen (open-folder flow).
                onBack = { selectedSession = null },
            )
        }
        selectedProject != null -> {
            BackHandler { selectedProject = null }
            SessionListScreen(
                server = appViewModel.server,
                project = selectedProject!!,
                busySessions = busySessions,
                onSessionClick = { selectedSession = it },
                onBack = { selectedProject = null },
            )
        }
        else -> {
            // Project list is the app's home while connected — let the back gesture
            // leave the app (default), NOT disconnect back to the Connect screen.
            ProjectListScreen(
                server = appViewModel.server,
                onProjectClick = { selectedProject = it },
                onSessionCreated = { selectedSession = it },
                onDisconnect = { appViewModel.disconnect() },
            )
        }
    }
}

private val navJson = Json { ignoreUnknownKeys = true }

/** A [Saver] that persists a nullable @Serializable nav selection as JSON in the
 *  saved-instance-state Bundle, so it survives Activity recreation (rotation). */
private inline fun <reified T> jsonNavSaver(): Saver<T?, String> = Saver(
    save = { value -> value?.let { navJson.encodeToString(it) } ?: "" },
    restore = { s -> if (s.isEmpty()) null else runCatching { navJson.decodeFromString<T>(s) }.getOrNull() },
)

@Composable
private fun SessionDestination(
    server: ServerConnection,
    session: Session,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // A fresh SessionViewModel per session id, keyed so switching sessions resets it.
    val vm = remember(session.id) {
        SessionViewModel(
            server,
            session,
            studio.eugenezakharov.opencode.api.AndroidComposerPrefs(context),
            injectTestPermission = UiTestFlags.injectPermission,
            injectTestQuestion = UiTestFlags.injectQuestion,
            injectQuestionJson = UiTestFlags.injectQuestionJson,
            injectTestTodo = UiTestFlags.injectTodo,
            cache = studio.eugenezakharov.opencode.api.MessageCache(context),
            streamLive = !UiTestFlags.disableStream,
        )
    }
    // Returning to the foreground re-syncs the snapshot (parity with iOS's
    // foregroundNonce). Android's stream loop already self-reconnects; this just
    // catches up any state missed during a background gap.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    SessionScreen(viewModel = vm, session = session, onBack = onBack)
}
