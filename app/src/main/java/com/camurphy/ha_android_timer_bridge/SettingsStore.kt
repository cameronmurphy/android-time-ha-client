package com.camurphy.ha_android_timer_bridge

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Settings, stored with DataStore and mirrored in memory.
 *
 * The mirror is the point. DataStore is asynchronous, but the listener reads settings inside
 * onNotificationPosted — a callback the system makes on its own thread, which cannot suspend
 * and must not block. So the flow is collected once and its latest value kept in a
 * StateFlow: coroutine callers observe [settings], everyone else reads [current].
 */
object SettingsStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** The settings as of the last read. Safe to call from any thread. */
    val current: Settings get() = _settings.value

    private var started = false

    /**
     * Prime the mirror and keep it current.
     *
     * The first read is blocking, deliberately: it happens once, in a component's onCreate,
     * and without it a notification arriving in the first moments after launch would be
     * judged against default settings rather than the real ones. Blocking once at startup
     * is the cost of never blocking in the callback.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val store = context.applicationContext.settingsDataStore
        runBlocking { _settings.value = store.data.map { it.toSettings() }.first() }
        scope.launch { store.data.collect { _settings.value = it.toSettings() } }
    }

    /** Apply a change and refresh the mirror from the result before returning. */
    suspend fun update(context: Context, transform: (Settings) -> Settings) {
        val store = context.applicationContext.settingsDataStore
        _settings.value = store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[KEY_WEBHOOK] = next.webhookUrl.trim()
            prefs[KEY_ENABLED] = next.enabled
            prefs[KEY_LOG_ALL] = next.logAll
            prefs[KEY_FORWARD_ALL] = next.forwardEverything
            prefs[KEY_DEVICE] = next.deviceName.trim()
            prefs[KEY_PACKAGES] = next.packagesRaw.trim()
        }.toSettings()
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            webhookUrl = this[KEY_WEBHOOK] ?: defaults.webhookUrl,
            enabled = this[KEY_ENABLED] ?: defaults.enabled,
            logAll = this[KEY_LOG_ALL] ?: defaults.logAll,
            forwardEverything = this[KEY_FORWARD_ALL] ?: defaults.forwardEverything,
            deviceName = this[KEY_DEVICE]?.takeIf { it.isNotBlank() } ?: defaults.deviceName,
            packagesRaw = this[KEY_PACKAGES] ?: defaults.packagesRaw,
        )
    }

    private val KEY_WEBHOOK = stringPreferencesKey("webhook_url")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_LOG_ALL = booleanPreferencesKey("log_all")
    private val KEY_FORWARD_ALL = booleanPreferencesKey("forward_everything")
    private val KEY_DEVICE = stringPreferencesKey("device_name")
    private val KEY_PACKAGES = stringPreferencesKey("packages")
}
