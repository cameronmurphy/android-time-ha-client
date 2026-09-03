package com.camurphy.ha_android_timer_bridge

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.UUID

private val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore("pairing")

/**
 * Stable identity for this tablet plus the pairing state Home Assistant sets up.
 *
 * The pairing code is what stops any other machine on the LAN from repointing this tablet at
 * a webhook of its choosing: Home Assistant has to quote it back during setup, and it is only
 * visible on the tablet's own screen.
 *
 * Mirrored in memory like [SettingsStore], and for the same reason — the mDNS advertisement
 * and the pairing server both need the current values without suspending.
 */
object PairingStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pairing = MutableStateFlow(Pairing())
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    val current: Pairing get() = _pairing.value

    private var started = false

    /** Prime the mirror, minting an identity and a pairing code on first run. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val store = context.applicationContext.pairingDataStore
        runBlocking {
            // Give this tablet an id and a code the first time, so the UI and the mDNS
            // advertisement have something to show before anyone has paired anything.
            store.edit { prefs ->
                if (prefs[KEY_ID].isNullOrBlank()) prefs[KEY_ID] = UUID.randomUUID().toString()
                if (prefs[KEY_CODE].isNullOrBlank()) prefs[KEY_CODE] = mintCode()
            }
            _pairing.value = store.data.map { it.toPairing() }.first()
        }
        scope.launch { store.data.collect { _pairing.value = it.toPairing() } }
    }

    suspend fun newPairingCode(context: Context) = edit(context) { it[KEY_CODE] = mintCode() }

    suspend fun pair(context: Context, webhookUrl: String, instanceName: String?) = edit(context) {
        it[KEY_WEBHOOK] = webhookUrl
        it[KEY_INSTANCE] = instanceName.orEmpty()
        it[KEY_PAIRED_AT] = System.currentTimeMillis()
    }

    suspend fun unpair(context: Context) = edit(context) {
        it.remove(KEY_WEBHOOK)
        it.remove(KEY_INSTANCE)
        it.remove(KEY_PAIRED_AT)
    }

    fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android tablet" }

    /**
     * Apply a change and refresh the mirror from the result before returning.
     *
     * Awaited rather than launched so that whoever asked for the change sees it: /pair
     * answers with the state it just wrote, and the settings screen redraws with the new
     * pairing code rather than the old one.
     */
    private suspend fun edit(context: Context, block: (MutablePreferences) -> Unit) {
        val result = context.applicationContext.pairingDataStore.edit(block)
        _pairing.value = result.toPairing()
    }

    private fun mintCode(): String = (100_000 + SecureRandom().nextInt(900_000)).toString()

    private fun Preferences.toPairing() = Pairing(
        deviceId = this[KEY_ID].orEmpty(),
        pairingCode = this[KEY_CODE].orEmpty(),
        webhookUrl = this[KEY_WEBHOOK]?.takeIf { it.isNotBlank() },
        instanceName = this[KEY_INSTANCE]?.takeIf { it.isNotBlank() },
        pairedAtMs = this[KEY_PAIRED_AT] ?: 0L,
    )

    private val KEY_ID = stringPreferencesKey("device_id")
    private val KEY_CODE = stringPreferencesKey("pairing_code")
    private val KEY_WEBHOOK = stringPreferencesKey("webhook_url")
    private val KEY_INSTANCE = stringPreferencesKey("instance_name")
    private val KEY_PAIRED_AT = longPreferencesKey("paired_at")
}
