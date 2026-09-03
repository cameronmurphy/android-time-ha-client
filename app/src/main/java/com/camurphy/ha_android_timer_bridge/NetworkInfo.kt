package com.camurphy.ha_android_timer_bridge

import java.net.Inet4Address
import java.net.NetworkInterface

/** Best-effort LAN address, shown so you can set the integration up by hand if mDNS fails. */
object NetworkInfo {

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
