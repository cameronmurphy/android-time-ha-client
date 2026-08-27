package com.camurphy.android_time_ha_client

import android.app.Notification
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Reads the text out of a notification's custom layout.
 *
 * Google Clock builds its firing-timer notification entirely from RemoteViews and sets none
 * of the standard title/text extras, so the timer's label only exists inside that layout.
 * Inflating it here and walking the view tree is the only way to recover the name.
 */
object RemoteViewsScraper {

    private const val TAG = "HaTimerBridge"

    /** Must be called from a thread with a Looper; the listener callback is on the main one. */
    fun scrape(context: Context, notification: Notification): List<String> {
        val texts = LinkedHashSet<String>()
        val layouts = listOfNotNull(
            notification.bigContentView,
            notification.contentView,
            notification.headsUpContentView,
        )
        for (views in layouts) {
            runCatching {
                val inflated = views.apply(context, FrameLayout(context))
                collect(inflated, texts)
            }.onFailure { Log.d(TAG, "could not inflate notification layout: ${it.message}") }
        }
        return texts.toList()
    }

    private fun collect(view: View, out: MutableSet<String>) {
        when (view) {
            is TextView -> view.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += it }
            is ViewGroup -> for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }
}
