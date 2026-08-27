package com.camurphy.android_time_ha_client

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Posts JSON to a Home Assistant webhook. Deliberately dependency-free: HttpURLConnection
 * on a single background thread, with a few retries so a brief Wi-Fi hiccup does not eat
 * a timer.
 */
object HaClient {

    private const val TAG = "HaTimerBridge"
    private val BACKOFF_MS = longArrayOf(0, 2_000, 6_000)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ha-client").apply { isDaemon = true }
    }

    /** @param onResult called on the background thread with a short human-readable status. */
    fun post(url: String, payload: JSONObject, onResult: (String) -> Unit) {
        if (url.isBlank()) {
            onResult("not sent: no webhook URL configured")
            return
        }
        executor.execute {
            var last = "unknown error"
            for ((attempt, delay) in BACKOFF_MS.withIndex()) {
                if (delay > 0) {
                    runCatching { Thread.sleep(delay) }
                }
                last = attempt(url, payload)
                if (last.startsWith("sent")) break
                Log.w(TAG, "POST attempt ${attempt + 1} failed: $last")
            }
            onResult(last)
        }
    }

    private fun attempt(url: String, payload: JSONObject): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                "sent (HTTP $code)"
            } else {
                val body = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                "failed: HTTP $code ${body.take(120)}".trim()
            }
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
        } finally {
            conn?.disconnect()
        }
    }
}
