package com.camurphy.ha_android_timer_bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The local network permission Android 17 introduced.
 *
 * mDNS registration is one of the things it gates: without it `registerService` throws
 * "Missing local network permission" and the tablet never appears in Home Assistant. The
 * failure is silent from the outside — the pairing server still listens and answers, so
 * everything looks healthy except that nothing can find it.
 *
 * Named as a string rather than through Manifest.permission so the app still builds against
 * an SDK that predates the constant.
 */
object LocalNetwork {

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The release that started gating the local network. */
    private const val GATED_FROM = 37

    /** Does this device gate the local network at all? */
    val gated: Boolean get() = Build.VERSION.SDK_INT >= GATED_FROM

    /** True when mDNS can be registered — either the platform does not gate it, or we hold it. */
    fun granted(context: Context): Boolean = !gated ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
