package studio.eugenezakharov.opencode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import studio.eugenezakharov.opencode.api.ConnectionStore
import studio.eugenezakharov.opencode.ui.AppNav
import studio.eugenezakharov.opencode.ui.AppViewModel
import studio.eugenezakharov.opencode.ui.UiTestFlags
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
        // UI tests inject a synthetic permission / question so the docks can be driven.
        UiTestFlags.injectPermission = intent?.getBooleanExtra("UITEST_PERMISSION", false) == true
        UiTestFlags.injectQuestion = intent?.getBooleanExtra("UITEST_QUESTION", false) == true
        UiTestFlags.injectTodo = intent?.getBooleanExtra("UITEST_TODO", false) == true
        UiTestFlags.injectQuestionJson = intent?.getStringExtra("UITEST_QUESTION_JSON")
        enableEdgeToEdge()
        requestNotificationPermission()
        handlePairingIntent(intent)
        handleOpenSessionIntent(intent)
        setContent {
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(appViewModel)
                }
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Ask for POST_NOTIFICATIONS on Android 13+ so idle pushes can be shown. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
        handleOpenSessionIntent(intent)
    }

    /** Handle opencode://pair?relay=…&tunnel=…&token=… deep links. */
    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "opencode") {
            appViewModel.applyPairingAndConnect(data.toString())
        }
    }

    /** A tapped push notification carries the session id it's about; open it. */
    private fun handleOpenSessionIntent(intent: Intent?) {
        appViewModel.requestOpenSession(intent?.getStringExtra("sessionId"))
    }
}
