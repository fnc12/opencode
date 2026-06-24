package studio.eugenezakharov.opencode

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import studio.eugenezakharov.opencode.api.ConnectionStore
import studio.eugenezakharov.opencode.ui.AppNav
import studio.eugenezakharov.opencode.ui.AppViewModel
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Instrumented tests pass UITEST_RESET to start from a clean (disconnected)
        // state. Must run before AppViewModel is first accessed (it loads the
        // saved config on construction).
        if (intent?.getBooleanExtra("UITEST_RESET", false) == true) {
            ConnectionStore(applicationContext).clear()
        }
        enableEdgeToEdge()
        handlePairingIntent(intent)
        setContent {
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(appViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    /** Handle opencode://pair?relay=…&tunnel=…&token=… deep links. */
    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "opencode") {
            appViewModel.applyPairingAndConnect(data.toString())
        }
    }
}
