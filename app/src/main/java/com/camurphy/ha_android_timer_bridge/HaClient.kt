package com.camurphy.ha_android_timer_bridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts JSON to a Home Assistant webhook.
 *
 * Deliberately dependency-free: HttpURLConnection rather than a client library, since the
 * app makes a handful of small POSTs and nothing else. A few retries so a brief Wi-Fi
 * hiccup does not eat a timer.
 */
object HaClient {

    private const val TAG = "HaTimerBridge"
    private val BACKOFF_MS = longArrayOf(0, 2_000, 6_000)

    /**
     * Post the payload, retrying a couple of times, and return a short human-readable
     * status. Suspends on the IO dispatcher; the caller decides what scope it runs in.
     */
    suspend fun post(url: String, payload: JSONObject): String {
        if (url.isBlank()) return "not sent: no webhook URL configured"

        return withContext(Dispatchers.IO) {
            var last = "unknown error"
            for ((attempt, backoff) in BACKOFF_MS.withIndex()) {
                if (backoff > 0) delay(backoff)
                last = attempt(url, payload)
                if (last.startsWith("sent")) break
                Log.w(TAG, "POST attempt ${attempt + 1} failed: $last")
            }
            last
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
