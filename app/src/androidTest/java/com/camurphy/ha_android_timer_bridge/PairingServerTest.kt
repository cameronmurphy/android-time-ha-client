package com.camurphy.ha_android_timer_bridge

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drives the real pairing server over a real socket.
 *
 * This is the half of the app the JVM tests cannot reach: a ServerSocket, a coroutine accept
 * loop, and DataStore underneath. The pairing code check is the tablet's only defence against
 * anything else on the network repointing it at a webhook of its choosing, so it is worth
 * testing against the server rather than against a mock of it.
 */
class PairingServerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: PairingServer
    private var port = 0

    @Before
    fun setUp() {
        SettingsStore.start(context)
        PairingStore.start(context)
        server = PairingServer(context) {}
        port = server.start()
        assertNotEquals("server should have bound a port", 0, port)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun get(path: String): Pair<Int, JSONObject> = request("GET", path, null)

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> =
        request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        body?.let { conn.outputStream.use { out -> out.write(it.toString().toByteArray()) } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return code to (runCatching { JSONObject(text) }.getOrElse { JSONObject() })
    }

    @Test
    fun info_describes_the_tablet_without_a_pairing_code() {
        val (code, body) = get("/info")
        assertEquals(200, code)
        assertTrue("device id should be set", body.getString("id").isNotBlank())
        assertEquals("ha-android-timer-bridge", body.getString("app"))
    }

    @Test
    fun pairing_is_refused_without_the_code_shown_on_the_tablet() {
        val (code, _) = post(
            "/pair",
            JSONObject().put("code", "000000").put("webhook_url", "http://example.invalid/hook"),
        )
        assertEquals(403, code)
        assertEquals(false, get("/info").second.getBoolean("paired"))
    }

    @Test
    fun pairing_with_the_right_code_takes_effect_immediately() {
        val pairingCode = PairingStore.current.pairingCode
        val (code, body) = post(
            "/pair",
            JSONObject()
                .put("code", pairingCode)
                .put("webhook_url", "http://example.invalid/hook")
                .put("instance_name", "Test HA"),
        )
        assertEquals(200, code)
        // The response must describe the state it just wrote, not the one it replaced.
        assertEquals(true, body.getBoolean("paired"))
        assertEquals("Test HA", body.getString("instance_name"))

        val (_, after) = post("/unpair", JSONObject().put("code", pairingCode))
        assertEquals(false, after.getBoolean("paired"))
    }

    @Test
    fun an_unknown_path_is_not_found() {
        assertEquals(404, get("/nope").first)
    }
}
