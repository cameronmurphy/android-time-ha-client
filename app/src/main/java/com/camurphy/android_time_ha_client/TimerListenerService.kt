package com.camurphy.android_time_ha_client

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Watches the notification shade for a timer going off and forwards it to Home Assistant.
 *
 * Android binds this service itself once the user grants notification access, and rebinds
 * it after reboots and crashes, so there is no foreground service to keep alive.
 */
class TimerListenerService : NotificationListenerService() {

    private lateinit var prefs: Prefs

    /**
     * Notification key -> the label and time we last forwarded for it.
     *
     * A ringing timer re-posts itself as its clock ticks, and Google Clock reuses the same
     * notification id for every timer, so keying on the notification alone is not enough:
     * we suppress repeats until the notification is dismissed, but always let a different
     * label through in case a removal was missed.
     */
    private val lastSent = HashMap<String, Pair<String?, Long>>()

    /**
     * The most recent label seen on a timer notification from a watched package, with when
     * we saw it. Some builds put the name on the running countdown but not on the
     * notification that fires, so this carries it across.
     */
    private var recentLabel: Pair<String, Long>? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        EventLog.load(this)
        // The system keeps this service bound, so it is the natural owner of the pairing
        // server: once notification access is granted the tablet stays discoverable.
        BridgeServer.ensureRunning(this)
    }

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.w(TAG, "listener disconnected")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSent.remove(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val snapshot = snapshot(sbn)
        val watched = sbn.packageName in prefs.packages

        if (!watched) {
            if (prefs.logAll) EventLog.add(this, snapshot, matched = false, kind = null, timerName = null, reason = null)
            return
        }

        // Remember any label from this package, fired or not, for the fallback below.
        TimerMatcher.extractName(snapshot).first
            ?.let { recentLabel = it to System.currentTimeMillis() }

        var match = TimerMatcher.classify(snapshot, forwardEverything = prefs.forwardEverything)
        if (match != null && match.timerName == null) {
            val remembered = recentLabel
            if (remembered != null && System.currentTimeMillis() - remembered.second < LABEL_MEMORY_MS) {
                match = match.copy(timerName = remembered.first, nameSource = "earlier notification")
            }
        }
        if (match == null) {
            if (prefs.logAll) EventLog.add(this, snapshot, matched = false, kind = null, timerName = null, reason = null)
            return
        }

        val now = System.currentTimeMillis()
        val previous = lastSent[sbn.key]
        if (previous != null &&
            previous.first == match.timerName &&
            now - previous.second < prefs.dedupeWindowMs
        ) {
            return
        }
        lastSent[sbn.key] = match.timerName to now

        val event = EventLog.add(this, snapshot, matched = true, kind = match.kind.wireName, timerName = match.timerName, reason = match.reason)
        if (!prefs.enabled) {
            EventLog.updateStatus(this, event.id, "not sent: forwarding is off")
            return
        }

        Log.i(TAG, "${match.kind.wireName}: name=${match.timerName} reason=${match.reason} kind=${match.kindReason}")
        val payload = Payload.build(snapshot, match, prefs.deviceName)
        Delivery.send(this, payload) { status ->
            EventLog.updateStatus(this, event.id, status)
        }
    }

    private fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val extras = n.extras
        val scrape = RemoteViewsScraper.scrape(this, n)
        fun cs(key: String): String? =
            extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        return NotificationSnapshot(
            packageName = sbn.packageName,
            channelId = n.channelId,
            category = n.category,
            title = cs(Notification.EXTRA_TITLE),
            titleBig = cs(Notification.EXTRA_TITLE_BIG),
            text = cs(Notification.EXTRA_TEXT),
            bigText = cs(Notification.EXTRA_BIG_TEXT),
            subText = cs(Notification.EXTRA_SUB_TEXT),
            infoText = cs(Notification.EXTRA_INFO_TEXT),
            summaryText = cs(Notification.EXTRA_SUMMARY_TEXT),
            hasFullScreenIntent = n.fullScreenIntent != null,
            isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
            postTimeMs = sbn.postTime,
            notificationId = sbn.id,
            tag = sbn.tag,
            actionTitles = n.actions?.mapNotNull { it.title?.toString() } ?: emptyList(),
            viewTexts = scrape.texts,
            extraTexts = extraTexts(extras),
            extrasDump = dumpExtras(extras),
            scrapeDiagnostics = scrape.diagnostics,
            tickerText = n.tickerText?.toString(),
        )
    }

    /** Extras keys we already read by name, so the dump does not repeat them. */
    private val KNOWN_EXTRAS = setOf(
        Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
        Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
        Notification.EXTRA_SUMMARY_TEXT,
    )

    /** Text from extras keys we do not know about — where a device may hide the label. */
    private fun extraTexts(extras: android.os.Bundle): List<String> =
        extras.keySet().orEmpty()
            .filter { it !in KNOWN_EXTRAS }
            .mapNotNull { key ->
                @Suppress("DEPRECATION")
                (extras.get(key) as? CharSequence)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            .distinct()

    private fun dumpExtras(extras: android.os.Bundle): List<String> =
        extras.keySet().orEmpty().mapNotNull { key ->
            @Suppress("DEPRECATION")
            val value = extras.get(key) ?: return@mapNotNull null
            val rendered = when (value) {
                is CharSequence -> value.toString()
                is Array<*> -> value.joinToString(" | ") { it?.toString().orEmpty() }
                else -> value.toString()
            }.trim()
            if (rendered.isEmpty()) null else "$key=${rendered.take(160)}"
        }

    companion object {
        private const val TAG = "HaTimerBridge"

        /** How long a label from a running timer stays usable. Longer than any real timer. */
        private const val LABEL_MEMORY_MS = 4 * 60 * 60 * 1000L

        @Volatile
        var connected = false
            private set

        /**
         * Ask the system to bind the listener again.
         *
         * Android owns this service's lifecycle and normally rebinds it after a reboot or a
         * process death, but a listener can be left unbound after an app update or a crash
         * loop. This is the supported nudge.
         */
        fun requestRebind(context: Context) {
            runCatching {
                requestRebind(ComponentName(context, TimerListenerService::class.java))
            }
        }

        /** Whether the user has granted notification access to this app. */
        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val me = ComponentName(context, TimerListenerService::class.java)
            return enabled.split(':').any {
                val parsed = ComponentName.unflattenFromString(it)
                parsed != null && parsed.packageName == me.packageName
            }
        }
    }
}
