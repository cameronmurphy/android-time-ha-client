package com.camurphy.android_time_ha_client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camurphy.android_time_ha_client.BuildConfig
import com.camurphy.android_time_ha_client.LoggedEvent
import com.camurphy.android_time_ha_client.MainViewModel
import com.camurphy.android_time_ha_client.R
import com.camurphy.android_time_ha_client.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Actions the screen can trigger that need an Activity rather than the ViewModel. */
data class ScreenActions(
    val openNotificationAccessSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, actions: ScreenActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Header() }
            item { PairingSection(state, viewModel) }
            item { AccessSection(state, actions) }
            item { OptionsSection(state, viewModel) }
            item { LogHeader(state, viewModel) }
            items(state.events, key = { it.id }) { event ->
                EventRow(event) { viewModel.resend(event) }
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = stringResource(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun PairingSection(state: UiState, viewModel: MainViewModel) {
    Column {
        SectionTitle(stringResource(R.string.section_pairing))
        Text(
            text = if (state.paired) {
                stringResource(R.string.paired_with, state.pairedInstance ?: "Home Assistant")
            } else {
                stringResource(R.string.not_paired)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.paired) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Card(modifier = Modifier.padding(top = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.pairing_code),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = state.pairingCode,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = stringResource(R.string.pairing_code_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Text(
            text = buildString {
                appendLine("address:     ${state.address}")
                appendLine("advertised:  ${state.advertisedAs ?: "not advertising"}")
                append("device id:   ${state.deviceId}")
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::newPairingCode) {
                Text(stringResource(R.string.new_code))
            }
            OutlinedButton(onClick = viewModel::unpair, enabled = state.paired) {
                Text(stringResource(R.string.unpair))
            }
        }
    }
}

@Composable
private fun AccessSection(state: UiState, actions: ScreenActions) {
    Column {
        SectionTitle(stringResource(R.string.section_access))
        Text(
            text = when {
                !state.notificationAccess -> stringResource(R.string.access_missing)
                state.listenerConnected -> stringResource(R.string.access_connected)
                else -> stringResource(R.string.access_binding)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.notificationAccess) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Button(
            onClick = actions.openNotificationAccessSettings,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.open_access_settings))
        }

        Text(
            text = if (state.batteryExempt) {
                stringResource(R.string.battery_exempt)
            } else {
                stringResource(R.string.battery_active)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedButton(
            onClick = actions.openBatterySettings,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.battery_settings))
        }
    }
}

@Composable
private fun OptionsSection(state: UiState, viewModel: MainViewModel) {
    Column {
        SectionTitle(stringResource(R.string.section_options))

        OutlinedTextField(
            value = state.deviceName,
            onValueChange = viewModel::onDeviceNameChange,
            label = { Text(stringResource(R.string.device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.packages,
            onValueChange = viewModel::onPackagesChange,
            label = { Text(stringResource(R.string.watched_packages)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = state.webhookUrl,
            onValueChange = viewModel::onWebhookChange,
            label = { Text(stringResource(R.string.webhook_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        SwitchRow(stringResource(R.string.forwarding_enabled), state.forwardingEnabled, viewModel::onForwardingChange)
        SwitchRow(stringResource(R.string.log_all), state.logAll, viewModel::onLogAllChange)
        SwitchRow(stringResource(R.string.forward_everything), state.forwardEverything, viewModel::onForwardEverythingChange)

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = viewModel::save) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { viewModel.save(); viewModel.sendTest() }) {
                Text(stringResource(R.string.send_test))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogHeader(state: UiState, viewModel: MainViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.section_log),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = 20.dp, bottom = 4.dp),
            )
            OutlinedButton(onClick = viewModel::clearLog) { Text(stringResource(R.string.clear)) }
        }
        if (state.events.isEmpty()) {
            Text(
                text = stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

private val TIME_FORMAT = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

@Composable
private fun EventRow(event: LoggedEvent, onResend: () -> Unit) {
    val snapshot = event.snapshot
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = buildString {
                append(TIME_FORMAT.format(Date(event.receivedAtMs)))
                append("  ")
                append(if (event.matched) (event.kind ?: "matched").uppercase() else "ignored")
                event.timerName?.let { append("  \"$it\"") }
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (event.matched) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = buildString {
                appendLine("pkg:      ${snapshot.packageName}")
                appendLine("channel:  ${snapshot.channelId ?: "-"}   category: ${snapshot.category ?: "-"}")
                appendLine("title:    ${snapshot.title ?: "-"}")
                appendLine("text:     ${snapshot.text ?: "-"}")
                if (snapshot.viewTexts.isNotEmpty()) appendLine("layout:   ${snapshot.viewTexts}")
                if (snapshot.actionTitles.isNotEmpty()) appendLine("buttons:  ${snapshot.actionTitles}")
                event.reason?.let { appendLine("reason:   $it") }
                event.deliveryStatus?.let { append("delivery: $it") }
            }.trimEnd(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(onClick = onResend, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.resend))
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
