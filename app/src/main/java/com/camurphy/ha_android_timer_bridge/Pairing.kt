package com.camurphy.ha_android_timer_bridge

/** What this tablet knows about the Home Assistant it is paired with. */
data class Pairing(
    val deviceId: String = "",
    val pairingCode: String = "",
    val webhookUrl: String? = null,
    val instanceName: String? = null,
    val pairedAtMs: Long = 0L,
) {
    val isPaired: Boolean get() = !webhookUrl.isNullOrBlank()
}
