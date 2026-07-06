package studio.eugenezakharov.opencode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }

    when {
        !state.connected -> {
            selectedProject = null
            selectedSession = null
            ConnectScreen(state = state, viewModel = appViewModel)
        }
        selectedSession != null -> {
            SessionDestination(
                server = appViewModel.server,
                session = selectedSession!!,
                // Back returns to the session list if we came through a project,
                // otherwise straight to the projects screen (open-folder flow).
                onBack = { selectedSession = null },
            )
        }
        selectedProject != null -> {
            SessionListScreen(
                server = appViewModel.server,
                project = selectedProject!!,
                busySessions = busySessions,
                onSessionClick = { selectedSession = it },
                onBack = { selectedProject = null },
            )
        }
        else -> {
            ProjectListScreen(
                server = appViewModel.server,
                onProjectClick = { selectedProject = it },
                onSessionCreated = { selectedSession = it },
                onDisconnect = { appViewModel.disconnect() },
            )
        }
    }
}

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
            studio.eugenezakharov.opencode.api.ComposerPrefs(context),
            injectTestPermission = UiTestFlags.injectPermission,
            injectTestQuestion = UiTestFlags.injectQuestion,
            injectTestTodo = UiTestFlags.injectTodo,
        )
    }
    SessionScreen(viewModel = vm, session = session, onBack = onBack)
}
