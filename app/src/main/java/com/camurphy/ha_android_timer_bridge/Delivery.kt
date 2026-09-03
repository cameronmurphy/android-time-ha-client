package com.camurphy.ha_android_timer_bridge

import android.content.Context
import org.json.JSONObject

/**
 * Sends one timer event to Home Assistant.
 *
 * The webhook normally arrives from Home Assistant during pairing. The manual webhook in
 * settings is the fallback for when Home Assistant cannot reach this tablet to pair with
 * it — a different subnet, or mDNS blocked on the network.
 */
object Delivery {

    /** Deliver the payload and return a short human-readable status. */
    suspend fun send(context: Context, payload: JSONObject): String {
        val target = target(context) ?: return "not sent: not paired with Home Assistant"
        return HaClient.post(target, payload)
    }

    fun target(context: Context): String? {
        val paired = BridgeIdentity(context).webhookUrl
        if (!paired.isNullOrBlank()) return paired
        return Prefs(context).webhookUrl.takeIf { it.isNotBlank() }
    }
}
