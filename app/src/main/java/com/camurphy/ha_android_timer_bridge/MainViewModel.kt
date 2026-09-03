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
    /** Android 17 gates mDNS behind this; without it nothing can discover the tablet. */
    val localNetworkAccess: Boolean = true,
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

    private val app get() = getApplication<Application>()

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
            BridgeServer.advertisedAs.collect { name ->
                _state.value = _state.value.copy(advertisedAs = name)
            }
        }
        viewModelScope.launch {
            BridgeServer.pairingChanges.collect {
                refresh()
                _messages.value = if (PairingStore.current.isPaired) {
                    "Paired with Home Assistant"
                } else {
                    "Unpaired"
                }
            }
        }
    }

    /** Re-reads the things that change outside this screen, such as permissions. */
    fun refresh() {
        // Granted but unbound happens after an app update; this is the supported nudge.
        if (TimerListenerService.hasNotificationAccess(app) && !TimerListenerService.connected) {
            TimerListenerService.requestRebind(app)
        }
        _state.value = read().copy(
            deviceName = _state.value.deviceName.ifEmpty { SettingsStore.current.deviceName },
            packages = _state.value.packages.ifEmpty { SettingsStore.current.packagesRaw },
            webhookUrl = _state.value.webhookUrl.ifEmpty { SettingsStore.current.webhookUrl },
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
        viewModelScope.launch {
            SettingsStore.update(app) {
                it.copy(
                    deviceName = current.deviceName,
                    packagesRaw = current.packages,
                    webhookUrl = current.webhookUrl,
                    enabled = current.forwardingEnabled,
                    logAll = current.logAll,
                    forwardEverything = current.forwardEverything,
                )
            }
            _messages.value = app.getString(R.string.saved)
        }
    }

    fun newPairingCode() {
        viewModelScope.launch {
            PairingStore.newPairingCode(app)
            refresh()
        }
    }

    fun unpair() {
        viewModelScope.launch {
            PairingStore.unpair(app)
            refresh()
            _messages.value = app.getString(R.string.unpaired_toast)
        }
    }

    fun clearLog() = EventLog.clear(getApplication())

    fun sendTest() {
        val snapshot = NotificationSnapshot(
            packageName = app.packageName,
            channelId = "test",
            category = "alarm",
            title = "Pasta timer",
            text = "Time's up",
            postTimeMs = System.currentTimeMillis(),
        )
        val match = TimerMatcher.classify(snapshot)
        val payload = Payload.build(snapshot, match, SettingsStore.current.deviceName, isTest = true)
        val event = EventLog.add(
            app,
            snapshot,
            matched = true,
            kind = match?.kind?.wireName,
            timerName = match?.timerName,
            reason = "manual test",
        )
        viewModelScope.launch {
            val status = Delivery.send(app, payload)
            EventLog.updateStatus(app, event.id, status)
            _messages.value = status
        }
    }

    /** Replays a logged notification, for testing the Home Assistant side. */
    fun resend(event: LoggedEvent) {
        val match = TimerMatcher.classify(event.snapshot, forwardEverything = true)
        val payload = Payload.build(event.snapshot, match, SettingsStore.current.deviceName)
        viewModelScope.launch {
            val status = Delivery.send(app, payload)
            EventLog.updateStatus(app, event.id, status)
            _messages.value = status
        }
    }

    /** Called once the local network permission has been granted, to advertise at last. */
    fun localNetworkGranted() {
        BridgeServer.localNetworkGranted()
        refresh()
    }

    fun messageShown() {
        _messages.value = null
    }

    private fun update(change: UiState.() -> UiState) {
        _state.value = _state.value.change()
    }

    private fun read(): UiState {
        val power = app.getSystemService(PowerManager::class.java)
        return UiState(
            paired = PairingStore.current.isPaired,
            pairedInstance = PairingStore.current.instanceName,
            pairingCode = PairingStore.current.pairingCode,
            deviceId = PairingStore.current.deviceId,
            address = "${NetworkInfo.localIpv4() ?: "unknown"}:${BridgeServer.port.takeIf { it > 0 } ?: "-"}",
            advertisedAs = BridgeServer.advertisedAs.value,
            notificationAccess = TimerListenerService.hasNotificationAccess(app),
            localNetworkAccess = LocalNetwork.granted(app),
            listenerConnected = TimerListenerService.connected,
            batteryExempt = power?.isIgnoringBatteryOptimizations(app.packageName) == true,
            deviceName = SettingsStore.current.deviceName,
            packages = SettingsStore.current.packagesRaw,
            webhookUrl = SettingsStore.current.webhookUrl,
            forwardingEnabled = SettingsStore.current.enabled,
            logAll = SettingsStore.current.logAll,
            forwardEverything = SettingsStore.current.forwardEverything,
            events = EventLog.snapshot(app),
        )
    }
}
