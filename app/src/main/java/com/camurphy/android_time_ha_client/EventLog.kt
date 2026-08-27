package com.camurphy.android_time_ha_client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One notification we saw, plus what we decided to do about it. */
data class LoggedEvent(
    val id: Long,
    val receivedAtMs: Long,
    val snapshot: NotificationSnapshot,
    val matched: Boolean,
    val kind: String?,
    val timerName: String?,
    val reason: String?,
    /** null while in flight, then the delivery result. */
    var deliveryStatus: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("receivedAtMs", receivedAtMs)
        put("matched", matched)
        put("kind", kind ?: JSONObject.NULL)
        put("timerName", timerName ?: JSONObject.NULL)
        put("reason", reason ?: JSONObject.NULL)
        put("deliveryStatus", deliveryStatus ?: JSONObject.NULL)
        put("snapshot", JSONObject().apply {
            put("packageName", snapshot.packageName)
            put("channelId", snapshot.channelId ?: JSONObject.NULL)
            put("category", snapshot.category ?: JSONObject.NULL)
            put("title", snapshot.title ?: JSONObject.NULL)
            put("titleBig", snapshot.titleBig ?: JSONObject.NULL)
            put("text", snapshot.text ?: JSONObject.NULL)
            put("bigText", snapshot.bigText ?: JSONObject.NULL)
            put("subText", snapshot.subText ?: JSONObject.NULL)
            put("infoText", snapshot.infoText ?: JSONObject.NULL)
            put("summaryText", snapshot.summaryText ?: JSONObject.NULL)
            put("hasFullScreenIntent", snapshot.hasFullScreenIntent)
            put("isOngoing", snapshot.isOngoing)
            put("showsChronometer", snapshot.showsChronometer)
            put("postTimeMs", snapshot.postTimeMs)
            put("viewTexts", JSONArray().also { arr -> snapshot.viewTexts.forEach(arr::put) })
            put("notificationId", snapshot.notificationId)
            put("tag", snapshot.tag ?: JSONObject.NULL)
            put("actionTitles", JSONArray().also { arr -> snapshot.actionTitles.forEach(arr::put) })
            put("extraTexts", JSONArray().also { arr -> snapshot.extraTexts.forEach(arr::put) })
            put("extrasDump", JSONArray().also { arr -> snapshot.extrasDump.forEach(arr::put) })
            put("scrapeDiagnostics", JSONArray().also { arr -> snapshot.scrapeDiagnostics.forEach(arr::put) })
            put("tickerText", snapshot.tickerText ?: JSONObject.NULL)
        })
    }

    companion object {
        fun fromJson(o: JSONObject): LoggedEvent {
            val s = o.getJSONObject("snapshot")
            fun str(k: String) = if (s.isNull(k)) null else s.getString(k)
            return LoggedEvent(
                id = o.getLong("id"),
                receivedAtMs = o.getLong("receivedAtMs"),
                snapshot = NotificationSnapshot(
                    packageName = s.getString("packageName"),
                    channelId = str("channelId"),
                    category = str("category"),
                    title = str("title"),
                    titleBig = str("titleBig"),
                    text = str("text"),
                    bigText = str("bigText"),
                    subText = str("subText"),
                    infoText = str("infoText"),
                    summaryText = str("summaryText"),
                    hasFullScreenIntent = s.optBoolean("hasFullScreenIntent"),
                    isOngoing = s.optBoolean("isOngoing"),
                    showsChronometer = s.optBoolean("showsChronometer"),
                    postTimeMs = s.optLong("postTimeMs"),
                    viewTexts = s.optJSONArray("viewTexts")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    notificationId = s.optInt("notificationId"),
                    tag = str("tag"),
                    actionTitles = s.optJSONArray("actionTitles")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    extraTexts = s.optJSONArray("extraTexts")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    extrasDump = s.optJSONArray("extrasDump")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    scrapeDiagnostics = s.optJSONArray("scrapeDiagnostics")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    tickerText = str("tickerText"),
                ),
                matched = o.getBoolean("matched"),
                kind = if (o.isNull("kind")) null else o.getString("kind"),
                timerName = if (o.isNull("timerName")) null else o.getString("timerName"),
                reason = if (o.isNull("reason")) null else o.getString("reason"),
                deliveryStatus = if (o.isNull("deliveryStatus")) null else o.getString("deliveryStatus"),
            )
        }
    }
}

/**
 * A small persisted ring buffer of recent notifications. The listener service and the UI
 * live in the same process, so a plain in-memory list with a change callback is enough.
 */
object EventLog {

    private const val MAX = 100
    private const val PREF_FILE = "ha_timer_bridge_log"
    private const val KEY = "events"

    private val events = ArrayList<LoggedEvent>()
    private var nextId = 1L
    private var loaded = false

    /** Set by the UI while it is visible. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) events.add(LoggedEvent.fromJson(arr.getJSONObject(i)))
        }
        nextId = (events.maxOfOrNull { it.id } ?: 0L) + 1
    }

    @Synchronized
    fun add(
        context: Context,
        snapshot: NotificationSnapshot,
        matched: Boolean,
        kind: String?,
        timerName: String?,
        reason: String?,
    ): LoggedEvent {
        load(context)
        val event = LoggedEvent(
            id = nextId++,
            receivedAtMs = System.currentTimeMillis(),
            snapshot = snapshot,
            matched = matched,
            kind = kind,
            timerName = timerName,
            reason = reason,
        )
        events.add(0, event)
        while (events.size > MAX) events.removeAt(events.size - 1)
        persist(context)
        onChanged?.invoke()
        return event
    }

    @Synchronized
    fun updateStatus(context: Context, id: Long, status: String) {
        events.firstOrNull { it.id == id }?.deliveryStatus = status
        persist(context)
        onChanged?.invoke()
    }

    @Synchronized
    fun snapshot(context: Context): List<LoggedEvent> {
        load(context)
        return ArrayList(events)
    }

    @Synchronized
    fun clear(context: Context) {
        events.clear()
        persist(context)
        onChanged?.invoke()
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}
