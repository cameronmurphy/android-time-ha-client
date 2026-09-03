package com.camurphy.ha_android_timer_bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises this tablet over mDNS as `_hatimerbridge._tcp`, which is what the Home
 * Assistant integration watches for. The TXT record carries enough for Home Assistant to
 * name the discovered device and give the config entry a stable unique id.
 */
class NsdAdvertiser(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val identity = BridgeIdentity(appContext)

    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var registeredName: String? = null
        private set

    @Synchronized
    fun register(port: Int) {
        if (listener != null || port <= 0) return

        val info = NsdServiceInfo().apply {
            serviceName = Prefs(appContext).deviceName.take(SERVICE_NAME_LIMIT)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", identity.deviceId)
            setAttribute("device", Prefs(appContext).deviceName)
            setAttribute("version", BuildConfig.VERSION_NAME)
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Log.i(TAG, "advertising as ${info.serviceName} on $SERVICE_TYPE:$port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                registeredName = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        listener = registration
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                Log.w(TAG, "could not start mDNS: ${it.message}")
                listener = null
            }
    }

    @Synchronized
    fun unregister() {
        listener?.let { runCatching { nsdManager.unregisterService(it) } }
        listener = null
        registeredName = null
    }

    companion object {
        private const val TAG = "HaTimerBridge"
        /**
         * Deliberately not renamed with the rest of the project: DNS-SD limits a
         * service name to 15 characters (RFC 6763 §7) and "haandroidtimerbridge" is
         * 20. This is a wire identifier no one sees, and both ends already agree on
         * it, so leaving it alone also spares a coordinated tablet-and-HA update.
         */
        const val SERVICE_TYPE = "_hatimerbridge._tcp"

        /** mDNS instance names must stay short; the TXT record carries the full name. */
        private const val SERVICE_NAME_LIMIT = 40
    }
}
