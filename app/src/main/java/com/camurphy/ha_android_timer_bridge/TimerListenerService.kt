package com.camurphy.ha_android_timer_bridge

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

    /** The running timer's name, carried from its countdown to the notification that fires. */
    private val labels = LabelMemory(LABEL_MEMORY_MS)

    /**
     * Timer label -> how long that timer runs for, and when we worked that out.
     *
     * The notification that fires carries no duration: by then the countdown reads zero.
     * The running notification does, so the length is captured the moment the timer is
     * created and held until it goes off. The longest value seen wins, since the first
     * sighting is the closest to the full length.
     */
    private val timerLengths = HashMap<String, Pair<Long, Long>>()

    /** The most recently measured countdown, whatever it was called. */
    private var lastLength: Pair<Long, Long>? = null

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

        // The extracted name, never the raw label: Clock calls an unnamed timer "Time's up",
        // which the extractor strips to nothing, and taking the raw value would reinstate the
        // boilerplate it just removed.
        val label = TimerMatcher.extractName(snapshot).first

        // Only a running countdown says what the current timer is called. Recording the
        // firing notification here too would be worse than useless: it is the one that
        // tends to arrive nameless, and it must not overwrite the name we are holding for
        // it. A countdown with no name does clear what is held — that is how an unnamed
        // timer stops inheriting the last one's name.
        val remaining = remainingMs(sbn.notification.extras, snapshot)
        if (remaining != null && remaining > 0L) {
            labels.observeRunning(label, System.currentTimeMillis())
        }
        recordLength(label, sbn.notification.extras, snapshot)

        var match = TimerMatcher.classify(snapshot, forwardEverything = prefs.forwardEverything)
        if (match != null && match.timerName == null) {
            labels.recall(System.currentTimeMillis())?.let {
                match = match.copy(timerName = it, nameSource = "earlier notification")
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

        // Attach the length captured while this timer was counting down.
        val lengthKey = match.timerName ?: ""
        val recorded = timerLengths[lengthKey]
            ?: timerLengths[""]
            ?: lastLength?.takeIf { System.currentTimeMillis() - it.second < LABEL_MEMORY_MS }
        if (match.duration == null && recorded != null) {
            match = match.copy(duration = TimerMatcher.humanDuration(recorded.first))
        }
        timerLengths.remove(lengthKey)
        lastLength = null
        labels.clear()

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
            metricsLabel = metricsLabel(extras),
            appLabel = appLabel(extras, sbn.packageName),
        )
    }

    /**
     * Note how long a running timer has left, keeping the largest value seen for a label.
     *
     * Ignores anything at or past zero, which is the notification that fires.
     */
    private fun recordLength(label: String?, extras: android.os.Bundle, snapshot: NotificationSnapshot) {
        val remaining = remainingMs(extras, snapshot) ?: return
        if (remaining <= 0L) return
        val key = label ?: ""
        val now = System.currentTimeMillis()
        val existing = timerLengths[key]
        if (existing == null || remaining > existing.first) {
            timerLengths[key] = remaining to now
        }
        val previous = lastLength
        if (previous == null || now - previous.second > LABEL_MEMORY_MS || remaining > previous.first) {
            lastLength = remaining to now
        }
        timerLengths.entries.removeAll { now - it.value.second > LABEL_MEMORY_MS }
    }

    /** How long is left on this timer, from the metrics bundle or the rendered clock. */
    private fun remainingMs(extras: android.os.Bundle, snapshot: NotificationSnapshot): Long? {
        metricsValue(extras)?.getLong("zeroElapsedRealtime", 0L)?.takeIf { it > 0L }?.let {
            return it - android.os.SystemClock.elapsedRealtime()
        }
        return snapshot.viewTexts.firstNotNullOfOrNull { TimerMatcher.parseClock(it) }?.times(1000L)
    }

    private fun metricsValue(extras: android.os.Bundle): android.os.Bundle? =
        metricsBundles(extras).firstNotNullOfOrNull { it.getBundle("value") }

    /**
     * The entries of a MetricStyle notification's `android.metrics` extra.
     *
     * Held as a List on the builds seen so far, but the framework is free to hand this back
     * as a Parcelable array, so accept either rather than silently returning nothing.
     */
    private fun metricsBundles(extras: android.os.Bundle): List<android.os.Bundle> {
        @Suppress("DEPRECATION")
        val raw = extras.get("android.metrics") ?: return emptyList()
        val items: List<Any?> = when (raw) {
            is List<*> -> raw
            is Array<*> -> raw.toList()
            else -> return emptyList()
        }
        return items.filterIsInstance<android.os.Bundle>()
    }

    /**
     * The timer's name as a real field, from a MetricStyle notification.
     *
     * `android.metrics` holds an array of Bundles, each with a `label`. This is exact,
     * unlike reading it back out of rendered text.
     */
    private fun metricsLabel(extras: android.os.Bundle): String? =
        metricsBundles(extras)
            .firstNotNullOfOrNull { it.getString("label")?.trim()?.takeIf(String::isNotEmpty) }

    /**
     * The posting app's display name. Taken from the notification's own ApplicationInfo
     * where possible, which sidesteps package-visibility rules.
     */
    private fun appLabel(extras: android.os.Bundle, packageName: String): String? {
        @Suppress("DEPRECATION")
        val info = extras.get("android.appInfo") as? android.content.pm.ApplicationInfo
        info?.let { runCatching { it.loadLabel(packageManager).toString() }.getOrNull() }
            ?.let { return it }
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrNull()
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
