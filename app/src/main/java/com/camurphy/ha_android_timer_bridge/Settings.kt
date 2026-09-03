package com.camurphy.ha_android_timer_bridge

import android.os.Build

/**
 * Every setting, as one immutable value.
 *
 * A snapshot rather than a bag of getters because the listener reads several of these while
 * deciding what to do with a notification, and they should all come from the same moment.
 */
data class Settings(
    val webhookUrl: String = "",
    val enabled: Boolean = true,
    /** Log every notification from every app, not just the watched packages. Discovery aid. */
    val logAll: Boolean = true,
    /** Skip the timer heuristics and POST anything the watched packages produce. */
    val forwardEverything: Boolean = false,
    val deviceName: String = defaultDeviceName(),
    val packagesRaw: String = DEFAULT_PACKAGES.joinToString("\n"),
) {
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

    companion object {
        fun defaultDeviceName(): String =
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "android" }

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
