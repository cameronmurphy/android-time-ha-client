package com.camurphy.android_time_ha_client

import android.content.Context
import android.os.Build

/** Thin wrapper over SharedPreferences so the rest of the app talks in typed settings. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("ha_timer_bridge", Context.MODE_PRIVATE)

    var webhookUrl: String
        get() = sp.getString(KEY_WEBHOOK, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEBHOOK, v.trim()).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Log every notification from every app, not just the watched packages. Discovery aid. */
    var logAll: Boolean
        get() = sp.getBoolean(KEY_LOG_ALL, true)
        set(v) = sp.edit().putBoolean(KEY_LOG_ALL, v).apply()

    /** Skip the timer heuristics and POST anything the watched packages produce. */
    var forwardEverything: Boolean
        get() = sp.getBoolean(KEY_FORWARD_ALL, false)
        set(v) = sp.edit().putBoolean(KEY_FORWARD_ALL, v).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE, "") ?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        set(v) = sp.edit().putString(KEY_DEVICE, v.trim()).apply()

    var packagesRaw: String
        get() = sp.getString(KEY_PACKAGES, DEFAULT_PACKAGES.joinToString("\n")) ?: ""
        set(v) = sp.edit().putString(KEY_PACKAGES, v.trim()).apply()

    val packages: Set<String>
        get() = packagesRaw.split(',', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Backstop for the dedupe in the listener. Normally a repeat is released as soon as the
     * notification is dismissed; this only matters if that removal is ever missed, so it is
     * long enough that a ringing timer never notifies twice in a sitting.
     */
    val dedupeWindowMs: Long get() = 60 * 60 * 1000L

    private fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "android" }

    companion object {
        private const val KEY_WEBHOOK = "webhook_url"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOG_ALL = "log_all"
        private const val KEY_FORWARD_ALL = "forward_everything"
        private const val KEY_DEVICE = "device_name"
        private const val KEY_PACKAGES = "packages"

        /**
         * Packages that own timers on a Pixel Tablet. Hub Mode routes Assistant timers
         * through one of these, but which one varies by build — hence the discovery log.
         */
        val DEFAULT_PACKAGES = listOf(
            "com.google.android.deskclock",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.assistant",
            "com.google.android.apps.chromecast.app",
            "com.android.deskclock",
        )
    }
}
