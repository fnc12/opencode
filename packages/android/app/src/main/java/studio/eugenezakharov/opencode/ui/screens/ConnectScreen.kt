package studio.eugenezakharov.opencode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import studio.eugenezakharov.opencode.api.ConnectionMode
import studio.eugenezakharov.opencode.ui.ConnectUiState
import studio.eugenezakharov.opencode.ui.ConnectViewModel

@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "opencode",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "Remote",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        ModePicker(mode = state.config.mode, onSelect = viewModel::setMode)

        Spacer(Modifier.height(20.dp))

        when (state.config.mode) {
            ConnectionMode.RELAY -> RelayFields(state, viewModel)
            ConnectionMode.DIRECT -> DirectFields(state, viewModel)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::connect,
            enabled = !state.loading && state.config.isComplete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Connect")
            }
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

        if (state.connected) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Connected — v${state.version}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModePicker(mode: ConnectionMode, onSelect: (ConnectionMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == ConnectionMode.RELAY,
            onClick = { onSelect(ConnectionMode.RELAY) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Relay") }
        SegmentedButton(
            selected = mode == ConnectionMode.DIRECT,
            onClick = { onSelect(ConnectionMode.DIRECT) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Direct") }
    }
}

@Composable
private fun RelayFields(state: ConnectUiState, vm: ConnectViewModel) {
    MonoField("Relay URL", state.config.relayURL, vm::setRelayURL, KeyboardType.Uri)
    Spacer(Modifier.height(12.dp))
    MonoField("Tunnel ID", state.config.tunnelID, vm::setTunnelID)
    Spacer(Modifier.height(12.dp))
    MonoField("Token", state.config.token, vm::setToken, password = true)
}

@Composable
private fun DirectFields(state: ConnectUiState, vm: ConnectViewModel) {
    MonoField("Server URL", state.config.directURL, vm::setDirectURL, KeyboardType.Uri)
    Spacer(Modifier.height(12.dp))
    MonoField("Password (optional)", state.config.password ?: "", vm::setPassword, password = true)
}

@Composable
private fun MonoField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
}
