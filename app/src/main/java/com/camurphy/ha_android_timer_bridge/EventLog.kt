package com.camurphy.ha_android_timer_bridge

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
private val Context.logDataStore: DataStore<Preferences> by preferencesDataStore("event_log")

object EventLog {

    private const val MAX = 100

    private val KEY = stringPreferencesKey("events")

    /**
     * Writes are launched rather than awaited: the log is a diagnostic aid, and a timer must
     * never wait on disk to reach Home Assistant. The in-memory list is the source of truth
     * for this process; DataStore is how it survives a restart.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val entries = ArrayList<LoggedEvent>()
    private var nextId = 1L
    private var loaded = false

    private val _events = MutableStateFlow<List<LoggedEvent>>(emptyList())

    /** Recent notifications, newest first. */
    val events: StateFlow<List<LoggedEvent>> = _events.asStateFlow()

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        // A blocking read, once, when the log is first touched — the UI and the listener
        // both expect the list to be there as soon as they ask for it.
        val raw = runBlocking { context.applicationContext.logDataStore.data.first()[KEY] } ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) entries.add(LoggedEvent.fromJson(arr.getJSONObject(i)))
        }
        nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
        publish()
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
        entries.add(0, event)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        persist(context)
        publish()
        return event
    }

    @Synchronized
    fun updateStatus(context: Context, id: Long, status: String) {
        entries.firstOrNull { it.id == id }?.deliveryStatus = status
        persist(context)
        publish()
    }

    @Synchronized
    fun snapshot(context: Context): List<LoggedEvent> {
        load(context)
        return ArrayList(entries)
    }

    @Synchronized
    fun clear(context: Context) {
        entries.clear()
        persist(context)
        publish()
    }

    private fun publish() {
        _events.value = ArrayList(entries)
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        val serialised = arr.toString()
        val store = context.applicationContext.logDataStore
        scope.launch { store.edit { it[KEY] = serialised } }
    }
}
