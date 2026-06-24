package studio.eugenezakharov.opencode.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import studio.eugenezakharov.opencode.ui.screens.ConnectScreen

object Routes {
    const val CONNECT = "connect"
    // Future (#14/#15): "projects", "sessions/{directory}", "session/{id}"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen()
        }
    }
}
