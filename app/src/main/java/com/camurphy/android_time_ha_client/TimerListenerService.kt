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

        val match = TimerMatcher.classify(snapshot, forwardEverything = prefs.forwardEverything)
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
            viewTexts = RemoteViewsScraper.scrape(this, n),
        )
    }

    companion object {
        private const val TAG = "HaTimerBridge"

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
