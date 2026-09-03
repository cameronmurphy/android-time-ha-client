package com.camurphy.ha_android_timer_bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Advertises this tablet over mDNS as `_hatimerbridge._tcp`, which is what the Home
 * Assistant integration watches for. The TXT record carries enough for Home Assistant to
 * name the discovered device and give the config entry a stable unique id.
 *
 * Registration is treated as something that will fail and has to be retried, because on a
 * tablet that starts with the power it usually does: the app comes up before Wi-Fi is
 * associated, mDNS registration fails, and a single attempt at process start would leave
 * the tablet invisible to Home Assistant until someone happened to reopen the app.
 */
class NsdAdvertiser(context: Context, private val onNameChanged: (String?) -> Unit = {}) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var listener: NsdManager.RegistrationListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var port: Int = 0
    private var attempt: Int = 0

    @Volatile
    var registeredName: String? = null
        private set(value) {
            field = value
            // Registration is asynchronous, so the name arrives well after register()
            // returns. Report it rather than leaving the UI on whatever it read first.
            onNameChanged(value)
        }

    @Synchronized
    fun register(port: Int) {
        if (port <= 0) return
        this.port = port
        watchNetwork()
        attempt = 0
        attemptRegistration()
    }

    @Synchronized
    private fun attemptRegistration() {
        if (listener != null || port <= 0) return

        // Retrying without the permission just re-throws; the UI asks for it instead, and
        // grantedLocalNetwork() brings us back here once it is held.
        if (!LocalNetwork.granted(appContext)) {
            Log.w(TAG, "not advertising: the local network permission has not been granted")
            return
        }

        val info = NsdServiceInfo().apply {
            serviceName = SettingsStore.current.deviceName.take(SERVICE_NAME_LIMIT)
            serviceType = SERVICE_TYPE
            setPort(this@NsdAdvertiser.port)
            setAttribute("id", PairingStore.current.deviceId)
            setAttribute("device", SettingsStore.current.deviceName)
            setAttribute("version", BuildConfig.VERSION_NAME)
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                synchronized(this@NsdAdvertiser) { attempt = 0 }
                registeredName = info.serviceName
                Log.i(TAG, "advertising as ${info.serviceName} on $SERVICE_TYPE:${this@NsdAdvertiser.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                registeredName = null
                // Clearing the listener is what makes a retry possible at all: register()
                // returns early while one is held, so leaving it set here would latch the
                // app into never advertising again for the life of the process.
                synchronized(this@NsdAdvertiser) { listener = null }
                scheduleRetry()
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredName = null
                synchronized(this@NsdAdvertiser) { listener = null }
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
                synchronized(this@NsdAdvertiser) { listener = null }
            }
        }

        listener = registration
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                Log.w(TAG, "could not start mDNS: ${it.message}")
                listener = null
                scheduleRetry()
            }
    }

    /** Try again now — the local network permission has just been granted. */
    @Synchronized
    fun grantedLocalNetwork() {
        attempt = 0
        attemptRegistration()
    }

    /** Back off up to a minute, then keep trying at that interval. */
    private fun scheduleRetry() {
        val delay = RETRY_MS[attempt.coerceAtMost(RETRY_MS.lastIndex)]
        synchronized(this) { attempt++ }
        handler.postDelayed({ attemptRegistration() }, delay)
    }

    /**
     * Re-advertise when a network arrives.
     *
     * Registration bound to a network that has since gone is not re-established by the
     * system, and the first attempt often lands before Wi-Fi is up at all.
     */
    @Synchronized
    private fun watchNetwork() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (registeredName == null) {
                    synchronized(this@NsdAdvertiser) { attempt = 0 }
                    attemptRegistration()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "could not watch the network: ${it.message}") }
    }

    @Synchronized
    fun unregister() {
        handler.removeCallbacksAndMessages(null)
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        listener?.let { runCatching { nsdManager.unregisterService(it) } }
        listener = null
        registeredName = null
        port = 0
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

        private val RETRY_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)
    }
}
