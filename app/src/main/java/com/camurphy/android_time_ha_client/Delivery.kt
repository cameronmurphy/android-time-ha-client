package com.camurphy.android_time_ha_client

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

    fun send(context: Context, payload: JSONObject, onStatus: (String) -> Unit) {
        val target = target(context)
        if (target == null) {
            onStatus("not sent: not paired with Home Assistant")
            return
        }
        HaClient.post(target, payload, onStatus)
    }

    fun target(context: Context): String? {
        val paired = BridgeIdentity(context).webhookUrl
        if (!paired.isNullOrBlank()) return paired
        return Prefs(context).webhookUrl.takeIf { it.isNotBlank() }
    }
}
