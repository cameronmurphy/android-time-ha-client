package com.camurphy.ha_android_timer_bridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A very small HTTP server so Home Assistant can find and pair with this tablet.
 *
 * Three endpoints, JSON in and out:
 *   GET  /info    what this device is, and whether it is already paired
 *   POST /pair    {code, webhook_url, instance_name} — stores the webhook to post timers to
 *   POST /unpair  {code} — forgets it
 *
 * It only ever accepts a webhook URL; it does not expose notifications or anything else.
 */
class PairingServer(
    context: Context,
    private val onPairingChanged: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val identity = BridgeIdentity(appContext)
    private val prefs = Prefs(appContext)
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "pair-conn").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** The port actually bound, for the mDNS advertisement. 0 when not running. */
    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start(): Int {
        if (serverSocket != null) return port
        val socket = bind() ?: return 0
        serverSocket = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "pair-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "pairing server listening on $port")
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    /** Prefer a predictable port, but never refuse to start because it is taken. */
    private fun bind(): ServerSocket? {
        for (candidate in intArrayOf(PREFERRED_PORT, 0)) {
            runCatching {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(candidate))
                }
            }.onFailure { Log.w(TAG, "could not bind port $candidate: ${it.message}") }
        }
        return null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "accept failed: ${e.message}")
                return
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            runCatching {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return respond(it.getOutputStream(), 400, error("Malformed request"))
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > MAX_BODY_BYTES) {
                    return respond(it.getOutputStream(), 413, error("Body too large"))
                }
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buffer, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buffer, 0, read)
                } else ""

                val (status, payload) = route(method, path, body)
                respond(it.getOutputStream(), status, payload)
            }.onFailure { e -> Log.w(TAG, "request failed: ${e.message}") }
        }
    }

    private fun route(method: String, path: String, body: String): Pair<Int, JSONObject> = when {
        method == "GET" && path == "/info" -> 200 to info()
        method == "POST" && path == "/pair" -> pair(body)
        method == "POST" && path == "/unpair" -> unpair(body)
        method == "POST" && path == "/diagnostics" -> diagnostics(body)
        else -> 404 to error("No such endpoint")
    }

    private fun info(): JSONObject = JSONObject().apply {
        put("app", "ha-android-timer-bridge")
        put("id", identity.deviceId)
        put("device", prefs.deviceName)
        put("version", BuildConfig.VERSION_NAME)
        put("paired", identity.isPaired)
        put("instance_name", identity.pairedInstanceName ?: JSONObject.NULL)
        put("notification_access", TimerListenerService.hasNotificationAccess(appContext))
        put("listener_connected", TimerListenerService.connected)
    }

    private fun pair(body: String): Pair<Int, JSONObject> {
        val json = runCatching { JSONObject(body) }.getOrElse {
            return 400 to error("Body must be JSON")
        }
        if (json.optString("code") != identity.pairingCode) {
            Log.w(TAG, "pairing rejected: wrong code")
            return 403 to error("Pairing code does not match the one shown on the tablet")
        }
        val webhook = json.optString("webhook_url").takeIf { it.isNotBlank() }
            ?: return 400 to error("webhook_url is required")

        identity.pair(webhook, json.optString("instance_name").takeIf { it.isNotBlank() })
        onPairingChanged()
        Log.i(TAG, "paired with ${json.optString("instance_name")}")
        return 200 to info()
    }

    private fun unpair(body: String): Pair<Int, JSONObject> {
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        if (json.optString("code") != identity.pairingCode) {
            return 403 to error("Pairing code does not match")
        }
        identity.unpair()
        onPairingChanged()
        return 200 to info()
    }

    /**
     * Recent notifications, for working out why something was or was not matched without
     * needing a cable. Behind the pairing code because it returns notification content.
     */
    private fun diagnostics(body: String): Pair<Int, JSONObject> {
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        if (json.optString("code") != identity.pairingCode) {
            return 403 to error("Pairing code does not match")
        }
        val limit = json.optInt("limit", 20).coerceIn(1, 100)
        val events = JSONArray()
        EventLog.snapshot(appContext).take(limit).forEach { events.put(it.toJson()) }
        return 200 to JSONObject().apply {
            put("device", prefs.deviceName)
            put("listener_connected", TimerListenerService.connected)
            put("watched_packages", JSONArray().also { a -> prefs.packages.forEach(a::put) })
            put("events", events)
        }
    }

    private fun error(message: String) = JSONObject().put("error", message)

    private fun respond(out: OutputStream, status: Int, payload: JSONObject) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "HaTimerBridge"
        private const val PREFERRED_PORT = 8127
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 16 * 1024
    }
}
