package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.ui.AppUiState
import studio.eugenezakharov.opencode.ui.AppViewModel

@Composable
fun ConnectScreen(
    state: AppUiState,
    viewModel: AppViewModel,
) {
    // While a connect attempt is in flight, lock every input so the config that's
    // being validated can't change underneath it. The user can still bail out via
    // Cancel instead of force-killing the app to escape a timeout.
    val loading = state.loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("shubat", style = MaterialTheme.typography.headlineLarge)
        Text(
            "for OpenCode",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.config.mode == ConnectionMode.RELAY,
                onClick = { viewModel.setMode(ConnectionMode.RELAY) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !loading,
            ) { Text("Relay") }
            SegmentedButton(
                selected = state.config.mode == ConnectionMode.DIRECT,
                onClick = { viewModel.setMode(ConnectionMode.DIRECT) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !loading,
                modifier = Modifier.testTag("connect.direct"),
            ) { Text("Direct") }
        }

        Spacer(Modifier.height(20.dp))

        when (state.config.mode) {
            ConnectionMode.RELAY -> {
                // QR scanner pairing is deferred — paste/deep-link covers it for now.
                // TODO(#14): add a CameraX QR scanner ("Scan pairing QR") here — it
                //           must also honor `enabled = !loading`.
                MonoField("Relay URL", state.config.relayURL, viewModel::setRelayURL, KeyboardType.Uri, enabled = !loading)
                Spacer(Modifier.height(12.dp))
                MonoField("Tunnel ID", state.config.tunnelID, viewModel::setTunnelID, enabled = !loading)
                Spacer(Modifier.height(12.dp))
                MonoField("Token", state.config.token, viewModel::setToken, password = true, enabled = !loading)
            }
            ConnectionMode.DIRECT -> {
                MonoField("Server URL", state.config.directURL, viewModel::setDirectURL, KeyboardType.Uri, tag = "connect.serverURL", enabled = !loading)
                Spacer(Modifier.height(12.dp))
                MonoField("Password (optional)", state.config.password ?: "", viewModel::setPassword, password = true, enabled = !loading)
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::connect,
            enabled = !state.loading && state.config.isComplete,
            modifier = Modifier.fillMaxWidth().testTag("connect.button"),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Connect")
            }
        }

        // Escape hatch while connecting: cancel the request cleanly instead of
        // waiting out the timeout (or killing the app).
        if (loading) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = viewModel::cancelConnect,
                modifier = Modifier.fillMaxWidth().testTag("connect.cancel"),
            ) { Text("Cancel") }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonoField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    tag: String? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
}
