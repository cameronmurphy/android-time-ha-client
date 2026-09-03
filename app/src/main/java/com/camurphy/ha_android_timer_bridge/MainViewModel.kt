package com.camurphy.ha_android_timer_bridge

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the settings screen draws. */
data class UiState(
    val paired: Boolean = false,
    val pairedInstance: String? = null,
    val pairingCode: String = "",
    val deviceId: String = "",
    val address: String = "",
    val advertisedAs: String? = null,
    val notificationAccess: Boolean = false,
    val listenerConnected: Boolean = false,
    val batteryExempt: Boolean = false,
    val deviceName: String = "",
    val packages: String = "",
    val webhookUrl: String = "",
    val forwardingEnabled: Boolean = true,
    val logAll: Boolean = true,
    val forwardEverything: Boolean = false,
    val events: List<LoggedEvent> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = Prefs(application)
    private val identity = BridgeIdentity(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot messages for the snackbar. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        BridgeServer.ensureRunning(application)
        EventLog.load(application)
        _state.value = read()

        viewModelScope.launch {
            EventLog.events.collect { events -> _state.value = _state.value.copy(events = events) }
        }
        viewModelScope.launch {
            BridgeServer.pairingChanges.collect {
                refresh()
                _messages.value = if (identity.isPaired) {
                    "Paired with Home Assistant"
                } else {
                    "Unpaired"
                }
            }
        }
    }

    /** Re-reads the things that change outside this screen, such as permissions. */
    fun refresh() {
        val app = getApplication<Application>()
        // Granted but unbound happens after an app update; this is the supported nudge.
        if (TimerListenerService.hasNotificationAccess(app) && !TimerListenerService.connected) {
            TimerListenerService.requestRebind(app)
        }
        _state.value = read().copy(
            deviceName = _state.value.deviceName.ifEmpty { prefs.deviceName },
            packages = _state.value.packages.ifEmpty { prefs.packagesRaw },
            webhookUrl = _state.value.webhookUrl.ifEmpty { prefs.webhookUrl },
        )
    }

    fun onDeviceNameChange(value: String) = update { copy(deviceName = value) }

    fun onPackagesChange(value: String) = update { copy(packages = value) }

    fun onWebhookChange(value: String) = update { copy(webhookUrl = value) }

    fun onForwardingChange(value: Boolean) = update { copy(forwardingEnabled = value) }

    fun onLogAllChange(value: Boolean) = update { copy(logAll = value) }

    fun onForwardEverythingChange(value: Boolean) = update { copy(forwardEverything = value) }

    fun save() {
        val current = _state.value
        prefs.deviceName = current.deviceName
        prefs.packagesRaw = current.packages
        prefs.webhookUrl = current.webhookUrl
        prefs.enabled = current.forwardingEnabled
        prefs.logAll = current.logAll
        prefs.forwardEverything = current.forwardEverything
        _messages.value = "Saved"
    }

    fun newPairingCode() {
        identity.newPairingCode()
        refresh()
    }

    fun unpair() {
        identity.unpair()
        refresh()
        _messages.value = "Unpaired — remove the device in Home Assistant too"
    }

    fun clearLog() = EventLog.clear(getApplication())

    fun sendTest() {
        val app = getApplication<Application>()
        val snapshot = NotificationSnapshot(
            packageName = app.packageName,
            channelId = "test",
            category = "alarm",
            title = "Pasta timer",
            text = "Time's up",
            postTimeMs = System.currentTimeMillis(),
        )
        val match = TimerMatcher.classify(snapshot)
        val payload = Payload.build(snapshot, match, prefs.deviceName, isTest = true)
        val event = EventLog.add(
            app,
            snapshot,
            matched = true,
            kind = match?.kind?.wireName,
            timerName = match?.timerName,
            reason = "manual test",
        )
        Delivery.send(app, payload) { status ->
            EventLog.updateStatus(app, event.id, status)
            _messages.value = status
        }
    }

    /** Replays a logged notification, for testing the Home Assistant side. */
    fun resend(event: LoggedEvent) {
        val app = getApplication<Application>()
        val match = TimerMatcher.classify(event.snapshot, forwardEverything = true)
        val payload = Payload.build(event.snapshot, match, prefs.deviceName)
        Delivery.send(app, payload) { status ->
            EventLog.updateStatus(app, event.id, status)
            _messages.value = status
        }
    }

    fun messageShown() {
        _messages.value = null
    }

    private fun update(change: UiState.() -> UiState) {
        _state.value = _state.value.change()
    }

    private fun read(): UiState {
        val app = getApplication<Application>()
        val power = app.getSystemService(PowerManager::class.java)
        return UiState(
            paired = identity.isPaired,
            pairedInstance = identity.pairedInstanceName,
            pairingCode = identity.pairingCode,
            deviceId = identity.deviceId,
            address = "${NetworkInfo.localIpv4() ?: "unknown"}:${BridgeServer.port.takeIf { it > 0 } ?: "-"}",
            advertisedAs = BridgeServer.advertisedAs,
            notificationAccess = TimerListenerService.hasNotificationAccess(app),
            listenerConnected = TimerListenerService.connected,
            batteryExempt = power?.isIgnoringBatteryOptimizations(app.packageName) == true,
            deviceName = prefs.deviceName,
            packages = prefs.packagesRaw,
            webhookUrl = prefs.webhookUrl,
            forwardingEnabled = prefs.enabled,
            logAll = prefs.logAll,
            forwardEverything = prefs.forwardEverything,
            events = EventLog.snapshot(app),
        )
    }
}
