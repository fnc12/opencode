package studio.eugenezakharov.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Scaffold
import studio.eugenezakharov.opencode.ui.AppNav
import studio.eugenezakharov.opencode.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App()
        }
    }
}

@Composable
fun App() {
    OpenCodeTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                AppNav()
            }
        }
    }
}
