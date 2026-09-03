package com.camurphy.ha_android_timer_bridge.ui

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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camurphy.ha_android_timer_bridge.BuildConfig
import com.camurphy.ha_android_timer_bridge.LoggedEvent
import com.camurphy.ha_android_timer_bridge.NotificationSnapshot
import com.camurphy.ha_android_timer_bridge.ui.theme.TimeHaClientTheme
import com.camurphy.ha_android_timer_bridge.MainViewModel
import com.camurphy.ha_android_timer_bridge.R
import com.camurphy.ha_android_timer_bridge.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Actions the screen can trigger that need an Activity rather than the ViewModel. */
data class ScreenActions(
    val openNotificationAccessSettings: () -> Unit = {},
    val openBatterySettings: () -> Unit = {},
)

/**
 * What the screen can ask the app to do.
 *
 * Sections take this rather than the ViewModel so each one is a function of its arguments —
 * which is what makes the previews below possible.
 */
data class ScreenCallbacks(
    val onDeviceNameChange: (String) -> Unit = {},
    val onPackagesChange: (String) -> Unit = {},
    val onWebhookChange: (String) -> Unit = {},
    val onForwardingChange: (Boolean) -> Unit = {},
    val onLogAllChange: (Boolean) -> Unit = {},
    val onForwardEverythingChange: (Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
    val onSendTest: () -> Unit = {},
    val onNewPairingCode: () -> Unit = {},
    val onUnpair: () -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onResend: (LoggedEvent) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, actions: ScreenActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val callbacks = remember(viewModel) {
        ScreenCallbacks(
            onDeviceNameChange = viewModel::onDeviceNameChange,
            onPackagesChange = viewModel::onPackagesChange,
            onWebhookChange = viewModel::onWebhookChange,
            onForwardingChange = viewModel::onForwardingChange,
            onLogAllChange = viewModel::onLogAllChange,
            onForwardEverythingChange = viewModel::onForwardEverythingChange,
            onSave = viewModel::save,
            onSendTest = { viewModel.save(); viewModel.sendTest() },
            onNewPairingCode = viewModel::newPairingCode,
            onUnpair = viewModel::unpair,
            onClearLog = viewModel::clearLog,
            onResend = viewModel::resend,
        )
    }
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
            item { PairingSection(state, callbacks) }
            item { AccessSection(state, actions) }
            item { OptionsSection(state, callbacks) }
            item { LogHeader(state, callbacks) }
            items(state.events, key = { it.id }) { event ->
                EventRow(event) { callbacks.onResend(event) }
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
private fun PairingSection(state: UiState, callbacks: ScreenCallbacks) {
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
            OutlinedButton(onClick = callbacks.onNewPairingCode) {
                Text(stringResource(R.string.new_code))
            }
            OutlinedButton(onClick = callbacks.onUnpair, enabled = state.paired) {
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
private fun OptionsSection(state: UiState, callbacks: ScreenCallbacks) {
    Column {
        SectionTitle(stringResource(R.string.section_options))

        OutlinedTextField(
            value = state.deviceName,
            onValueChange = callbacks.onDeviceNameChange,
            label = { Text(stringResource(R.string.device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.packages,
            onValueChange = callbacks.onPackagesChange,
            label = { Text(stringResource(R.string.watched_packages)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = state.webhookUrl,
            onValueChange = callbacks.onWebhookChange,
            label = { Text(stringResource(R.string.webhook_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        SwitchRow(stringResource(R.string.forwarding_enabled), state.forwardingEnabled, callbacks.onForwardingChange)
        SwitchRow(stringResource(R.string.log_all), state.logAll, callbacks.onLogAllChange)
        SwitchRow(stringResource(R.string.forward_everything), state.forwardEverything, callbacks.onForwardEverythingChange)

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = callbacks.onSave) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = callbacks.onSendTest) {
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
private fun LogHeader(state: UiState, callbacks: ScreenCallbacks) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.section_log),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = 20.dp, bottom = 4.dp),
            )
            OutlinedButton(onClick = callbacks.onClearLog) { Text(stringResource(R.string.clear)) }
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

// ---------------------------------------------------------------------------------------
// Previews. The sections take state and callbacks rather than a ViewModel, so Android
// Studio can render them without a running app — which is also the only way to see the
// unpaired, no-access and empty-log states without putting the tablet into them.
// ---------------------------------------------------------------------------------------

private val PAIRED_STATE = UiState(
    paired = true,
    pairedInstance = "Home",
    pairingCode = "417160",
    deviceId = "dee4540d-00d6-4c9d-98d0-b5ae83fec0b9",
    address = "192.168.0.44:8127",
    advertisedAs = "Pixel Tablet",
    notificationAccess = true,
    listenerConnected = true,
    deviceName = "Pixel Tablet",
    packages = "com.google.android.deskclock",
    webhookUrl = "",
)

@Preview(name = "Pairing — paired", showBackground = true)
@Composable
private fun PairingSectionPairedPreview() {
    TimeHaClientTheme { Surface { PairingSection(PAIRED_STATE, ScreenCallbacks()) } }
}

@Preview(name = "Pairing — not paired", showBackground = true)
@Composable
private fun PairingSectionUnpairedPreview() {
    TimeHaClientTheme {
        Surface {
            PairingSection(
                PAIRED_STATE.copy(paired = false, pairedInstance = null, advertisedAs = null),
                ScreenCallbacks(),
            )
        }
    }
}

@Preview(name = "Access — granted", showBackground = true)
@Composable
private fun AccessSectionGrantedPreview() {
    TimeHaClientTheme { Surface { AccessSection(PAIRED_STATE, ScreenActions()) } }
}

/** The state a new install opens in, and the one most worth getting right. */
@Preview(name = "Access — no notification access", showBackground = true)
@Composable
private fun AccessSectionDeniedPreview() {
    TimeHaClientTheme {
        Surface {
            AccessSection(
                PAIRED_STATE.copy(notificationAccess = false, listenerConnected = false),
                ScreenActions(),
            )
        }
    }
}

@Preview(name = "Options", showBackground = true)
@Composable
private fun OptionsSectionPreview() {
    TimeHaClientTheme { Surface { OptionsSection(PAIRED_STATE, ScreenCallbacks()) } }
}

@Preview(name = "Log — empty", showBackground = true)
@Composable
private fun LogHeaderEmptyPreview() {
    TimeHaClientTheme { Surface { LogHeader(PAIRED_STATE, ScreenCallbacks()) } }
}

@Preview(name = "Log row", showBackground = true)
@Composable
private fun EventRowPreview() {
    TimeHaClientTheme {
        Surface {
            EventRow(
                LoggedEvent(
                    id = 1,
                    receivedAtMs = 0L,
                    snapshot = NotificationSnapshot(
                        packageName = "com.google.android.deskclock",
                        channelId = "Timers v2",
                    ),
                    matched = true,
                    kind = "timer",
                    timerName = "chicken",
                    reason = "matched completion phrase",
                ).apply { deliveryStatus = "sent (HTTP 200)" },
            ) {}
        }
    }
}
