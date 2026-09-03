package com.camurphy.ha_android_timer_bridge

import android.content.Context
import android.os.Build
import java.security.SecureRandom
import java.util.UUID

/**
 * Stable identity for this tablet plus the pairing state Home Assistant sets up.
 *
 * The pairing code is what stops any other machine on the LAN from repointing this tablet
 * at a webhook of its choosing: Home Assistant has to quote it back during setup, and it is
 * only visible on the tablet's own screen.
 */
class BridgeIdentity(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("ha_android_timer_bridge_pairing", Context.MODE_PRIVATE)

    /** Stable across restarts; Home Assistant uses it as the config entry's unique id. */
    val deviceId: String
        get() = sp.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_ID, it).apply()
        }

    val pairingCode: String
        get() = sp.getString(KEY_CODE, null) ?: newPairingCode()

    fun newPairingCode(): String {
        val code = (100_000 + SecureRandom().nextInt(900_000)).toString()
        sp.edit().putString(KEY_CODE, code).apply()
        return code
    }

    /** Webhook Home Assistant handed us during pairing. Null until paired. */
    var webhookUrl: String?
        get() = sp.getString(KEY_WEBHOOK, null)
        private set(v) { sp.edit().putString(KEY_WEBHOOK, v).apply() }

    var pairedInstanceName: String?
        get() = sp.getString(KEY_INSTANCE, null)
        private set(v) { sp.edit().putString(KEY_INSTANCE, v).apply() }

    var pairedAtMs: Long
        get() = sp.getLong(KEY_PAIRED_AT, 0L)
        private set(v) { sp.edit().putLong(KEY_PAIRED_AT, v).apply() }

    val isPaired: Boolean get() = !webhookUrl.isNullOrBlank()

    fun pair(webhookUrl: String, instanceName: String?) {
        this.webhookUrl = webhookUrl
        this.pairedInstanceName = instanceName
        this.pairedAtMs = System.currentTimeMillis()
    }

    fun unpair() {
        webhookUrl = null
        pairedInstanceName = null
        pairedAtMs = 0L
    }

    fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android tablet" }

    companion object {
        private const val KEY_ID = "device_id"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_WEBHOOK = "webhook_url"
        private const val KEY_INSTANCE = "instance_name"
        private const val KEY_PAIRED_AT = "paired_at"
    }
}
