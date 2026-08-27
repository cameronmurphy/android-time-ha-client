package com.camurphy.android_time_ha_client

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Setup and diagnostics.
 *
 * Two things have to be true for the bridge to work: Home Assistant has paired with this
 * tablet, and Android has granted notification access. Each gets a section, and the
 * notification log at the bottom is the tuning tool — Hub Mode timer notifications differ
 * between builds, so you look at what actually arrived and adjust from there.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var identity: BridgeIdentity

    private lateinit var pairStatusView: TextView
    private lateinit var pairCodeView: TextView
    private lateinit var discoveryView: TextView
    private lateinit var unpairButton: Button
    private lateinit var accessStatusView: TextView
    private lateinit var batteryStatusView: TextView
    private lateinit var deviceField: EditText
    private lateinit var packagesField: EditText
    private lateinit var webhookField: EditText
    private lateinit var enabledBox: CheckBox
    private lateinit var logAllBox: CheckBox
    private lateinit var forwardAllBox: CheckBox
    private lateinit var logContainer: LinearLayout

    private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    private val green = Color.parseColor("#2E7D32")
    private val red = Color.parseColor("#C62828")
    private val grey = Color.parseColor("#757575")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        identity = BridgeIdentity(this)
        setContentView(buildUi())
        loadValues()
    }

    override fun onResume() {
        super.onResume()
        BridgeServer.ensureRunning(this)
        BridgeServer.onPairingChanged = {
            runOnUiThread {
                renderPairing()
                toast(if (identity.isPaired) "Paired with Home Assistant" else "Unpaired")
            }
        }
        EventLog.onChanged = { runOnUiThread { renderLog() } }
        renderPairing()
        renderAccessStatus()
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        EventLog.onChanged = null
        BridgeServer.onPairingChanged = null
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        root.addView(heading("Time HA Client"))
        root.addView(body("Forwards finished Google Assistant and Clock timers from this tablet to Home Assistant."))

        // 1. Pairing ---------------------------------------------------------------
        root.addView(sectionTitle("1. Pair with Home Assistant"))
        pairStatusView = body("")
        root.addView(pairStatusView)

        root.addView(label("Pairing code"))
        pairCodeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.2f
        }
        root.addView(pairCodeView)
        root.addView(body("Home Assistant asks for this code when it sets the tablet up. Only type it into your own Home Assistant."))

        discoveryView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE)
            setTextColor(grey)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(discoveryView)

        val pairRow = row()
        pairRow.addView(button("New code") {
            identity.newPairingCode()
            renderPairing()
        })
        unpairButton = button("Unpair") {
            identity.unpair()
            renderPairing()
            toast("Unpaired — remove the device in Home Assistant too")
        }
        pairRow.addView(unpairButton)
        root.addView(pairRow)

        // 2. Android side ----------------------------------------------------------
        root.addView(sectionTitle("2. Notification access on this tablet"))
        accessStatusView = body("")
        root.addView(accessStatusView)
        root.addView(button("Open notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        batteryStatusView = body("")
        root.addView(batteryStatusView)
        root.addView(button("Battery optimisation settings") {
            // The direct-request intent needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which is
            // a restricted permission; opening the list needs nothing. Find this app under
            // "All apps" and set it to Unrestricted.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        // 3. Options ---------------------------------------------------------------
        root.addView(sectionTitle("3. Options"))
        root.addView(label("Device name"))
        deviceField = field("Pixel Tablet")
        root.addView(deviceField)

        root.addView(label("Watched packages (one per line)"))
        packagesField = field("").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            setLines(5)
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(packagesField)

        root.addView(label("Webhook URL (only needed if Home Assistant cannot reach this tablet to pair)"))
        webhookField = field("http://homeassistant.local:8123/api/webhook/xxx")
        root.addView(webhookField)

        enabledBox = CheckBox(this).apply { text = "Forward timer events to Home Assistant" }
        logAllBox = CheckBox(this).apply { text = "Log notifications from every app (discovery)" }
        forwardAllBox = CheckBox(this).apply {
            text = "Forward everything from watched packages (skip timer detection)"
        }
        root.addView(enabledBox)
        root.addView(logAllBox)
        root.addView(forwardAllBox)

        val actionRow = row()
        actionRow.addView(button("Save") { save() })
        actionRow.addView(button("Send test event") { save(); sendTest() })
        root.addView(actionRow)

        // Log ----------------------------------------------------------------------
        val logHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(sectionTitle("Recent notifications").apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        logHeader.addView(button("Clear") { EventLog.clear(this); renderLog() })
        root.addView(logHeader)

        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(logContainer)

        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(root)
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- rendering

    private fun renderPairing() {
        val paired = identity.isPaired
        pairStatusView.text = if (paired) {
            "Paired with ${identity.pairedInstanceName ?: "Home Assistant"}."
        } else {
            "Not paired. In Home Assistant, go to Settings → Devices & services. This " +
                "tablet should appear as a discovered device — or add HA Timer Bridge by hand."
        }
        pairStatusView.setTextColor(if (paired) green else grey)
        pairCodeView.text = identity.pairingCode
        unpairButton.isEnabled = paired

        val port = BridgeServer.port
        discoveryView.text = buildString {
            appendLine("address:      ${NetworkInfo.localIpv4() ?: "unknown"}:${if (port > 0) port else "-"}")
            appendLine("mdns:         ${NsdAdvertiser.SERVICE_TYPE}")
            appendLine("advertised:   ${BridgeServer.advertisedAs ?: "not advertising"}")
            append("device id:    ${identity.deviceId}")
        }
    }

    private fun renderAccessStatus() {
        val granted = TimerListenerService.hasNotificationAccess(this)
        // Granted but unbound happens after an app update; this is the supported nudge.
        if (granted && !TimerListenerService.connected) {
            TimerListenerService.requestRebind(this)
        }
        val exempt = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        batteryStatusView.text = if (exempt) {
            "Battery optimisation: exempt. Timers get through even off the dock."
        } else {
            "Battery optimisation: active. Fine while docked and charging, but Doze can " +
                "delay events when the tablet runs on battery."
        }
        batteryStatusView.setTextColor(if (exempt) green else grey)
        accessStatusView.text = when {
            !granted -> "NOT GRANTED — tap below and switch on Time HA Client."
            TimerListenerService.connected -> "Granted, listener connected."
            else -> "Granted, waiting for the listener to bind."
        }
        accessStatusView.setTextColor(if (granted) green else red)
    }

    private fun loadValues() {
        deviceField.setText(prefs.deviceName)
        packagesField.setText(prefs.packagesRaw)
        webhookField.setText(prefs.webhookUrl)
        enabledBox.isChecked = prefs.enabled
        logAllBox.isChecked = prefs.logAll
        forwardAllBox.isChecked = prefs.forwardEverything
    }

    private fun save() {
        prefs.deviceName = deviceField.text.toString()
        prefs.packagesRaw = packagesField.text.toString()
        prefs.webhookUrl = webhookField.text.toString()
        prefs.enabled = enabledBox.isChecked
        prefs.logAll = logAllBox.isChecked
        prefs.forwardEverything = forwardAllBox.isChecked
        toast("Saved")
    }

    private fun sendTest() {
        val snapshot = NotificationSnapshot(
            packageName = packageName,
            channelId = "test",
            category = "alarm",
            title = "Pasta timer",
            text = "Time's up",
            postTimeMs = System.currentTimeMillis(),
        )
        val match = TimerMatcher.classify(snapshot)
        val payload = Payload.build(snapshot, match, prefs.deviceName, isTest = true)
        val event = EventLog.add(this, snapshot, matched = true, kind = match?.kind?.wireName, timerName = match?.timerName, reason = "manual test")
        Delivery.send(this, payload) { status ->
            EventLog.updateStatus(this, event.id, status)
            runOnUiThread { toast(status) }
        }
    }

    private fun renderLog() {
        logContainer.removeAllViews()
        val events = EventLog.snapshot(this)
        if (events.isEmpty()) {
            logContainer.addView(body("Nothing yet. Start a timer on the tablet, then come back."))
            return
        }
        for (event in events) logContainer.addView(logRow(event))
    }

    private fun logRow(event: LoggedEvent): View {
        val s = event.snapshot
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val header = buildString {
            append(timeFormat.format(Date(event.receivedAtMs)))
            append("  ")
            append(if (event.matched) (event.kind ?: "MATCHED").uppercase() else "ignored")
            if (event.timerName != null) append("  name=\"${event.timerName}\"")
        }
        row.addView(TextView(this).apply {
            text = header
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (event.matched) green else grey)
        })

        val detail = buildString {
            appendLine("pkg:      ${s.packageName}")
            appendLine("channel:  ${s.channelId ?: "-"}   category: ${s.category ?: "-"}")
            appendLine("title:    ${s.title ?: "-"}")
            appendLine("text:     ${s.text ?: "-"}")
            if (s.bigText != null) appendLine("bigText:  ${s.bigText}")
            if (s.subText != null) appendLine("subText:  ${s.subText}")
            appendLine("flags:    fullScreen=${s.hasFullScreenIntent} ongoing=${s.isOngoing} chrono=${s.showsChronometer}")
            if (event.reason != null) appendLine("reason:   ${event.reason}")
            if (event.deliveryStatus != null) append("delivery: ${event.deliveryStatus}")
        }.trimEnd()

        row.addView(TextView(this).apply {
            text = detail
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        row.addView(button("Send this to HA") {
            val match = TimerMatcher.classify(s, forwardEverything = true)
            val payload = Payload.build(s, match, prefs.deviceName)
            Delivery.send(this, payload) { status ->
                EventLog.updateStatus(this, event.id, status)
                runOnUiThread { toast(status) }
            }
        })

        return row
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
