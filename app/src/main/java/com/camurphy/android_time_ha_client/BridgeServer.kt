package com.camurphy.android_time_ha_client

import android.content.Context

/**
 * Owns the pairing server and its mDNS advertisement for the lifetime of the process.
 *
 * Both the notification listener and the settings screen ask for this, so the tablet is
 * discoverable while you are setting it up and stays discoverable afterwards — the listener
 * service keeps the process alive once notification access is granted.
 */
object BridgeServer {

    private var server: PairingServer? = null
    private var advertiser: NsdAdvertiser? = null

    /** Notified when Home Assistant pairs or unpairs, so the UI can refresh. */
    @Volatile
    var onPairingChanged: (() -> Unit)? = null

    val port: Int get() = server?.port ?: 0
    val advertisedAs: String? get() = advertiser?.registeredName

    @Synchronized
    fun ensureRunning(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val pairingServer = PairingServer(app) { onPairingChanged?.invoke() }
        val boundPort = pairingServer.start()
        if (boundPort == 0) return
        server = pairingServer
        advertiser = NsdAdvertiser(app).apply { register(boundPort) }
    }

    @Synchronized
    fun stop() {
        advertiser?.unregister()
        server?.stop()
        advertiser = null
        server = null
    }
}
