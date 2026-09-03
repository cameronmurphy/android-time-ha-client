package com.camurphy.ha_android_timer_bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _pairingChanges = MutableStateFlow(0)

    /** Increments whenever Home Assistant pairs or unpairs, so the UI can refresh. */
    val pairingChanges: StateFlow<Int> = _pairingChanges.asStateFlow()

    val port: Int get() = server?.port ?: 0
    val advertisedAs: String? get() = advertiser?.registeredName

    @Synchronized
    fun ensureRunning(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val pairingServer = PairingServer(app) { _pairingChanges.value++ }
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
