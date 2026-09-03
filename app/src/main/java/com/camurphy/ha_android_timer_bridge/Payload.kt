package com.camurphy.ha_android_timer_bridge

import org.json.JSONObject

/**
 * The JSON body posted to Home Assistant. Everything the notification gave us is included
 * raw alongside our best guess at the name, so an automation can fall back to the raw
 * fields if the parsing ever gets a label wrong.
 */
object Payload {

    fun build(
        snapshot: NotificationSnapshot,
        match: TimerMatch?,
        deviceName: String,
        isTest: Boolean = false,
    ): JSONObject = JSONObject().apply {
        put("event", match?.kind?.wireName ?: EventKind.TIMER.wireName)
        put("kind", if (match?.kind == EventKind.ALARM) "alarm" else "timer")
        put("kind_reason", match?.kindReason ?: JSONObject.NULL)
        put("device", deviceName)
        put("timer_name", match?.timerName ?: JSONObject.NULL)
        put("timer_name_source", match?.nameSource ?: JSONObject.NULL)
        put("duration", match?.duration ?: JSONObject.NULL)
        put("match_reason", match?.reason ?: JSONObject.NULL)
        put("is_test", isTest)
        put("fired_at_ms", System.currentTimeMillis())
        put("raw", JSONObject().apply {
            put("package", snapshot.packageName)
            put("channel_id", snapshot.channelId ?: JSONObject.NULL)
            put("category", snapshot.category ?: JSONObject.NULL)
            put("title", snapshot.title ?: JSONObject.NULL)
            put("title_big", snapshot.titleBig ?: JSONObject.NULL)
            put("text", snapshot.text ?: JSONObject.NULL)
            put("big_text", snapshot.bigText ?: JSONObject.NULL)
            put("sub_text", snapshot.subText ?: JSONObject.NULL)
            put("info_text", snapshot.infoText ?: JSONObject.NULL)
            put("summary_text", snapshot.summaryText ?: JSONObject.NULL)
            put("has_full_screen_intent", snapshot.hasFullScreenIntent)
            put("is_ongoing", snapshot.isOngoing)
            put("shows_chronometer", snapshot.showsChronometer)
            put("post_time_ms", snapshot.postTimeMs)
            put("view_texts", org.json.JSONArray().also { arr -> snapshot.viewTexts.forEach(arr::put) })
            put("action_titles", org.json.JSONArray().also { arr -> snapshot.actionTitles.forEach(arr::put) })
            put("notification_id", snapshot.notificationId)
            put("tag", snapshot.tag ?: JSONObject.NULL)
            put("ticker_text", snapshot.tickerText ?: JSONObject.NULL)
            put("extra_texts", org.json.JSONArray().also { a -> snapshot.extraTexts.forEach(a::put) })
            put("extras_dump", org.json.JSONArray().also { a -> snapshot.extrasDump.forEach(a::put) })
            put("scrape_diagnostics", org.json.JSONArray().also { a -> snapshot.scrapeDiagnostics.forEach(a::put) })
        })
    }
}
