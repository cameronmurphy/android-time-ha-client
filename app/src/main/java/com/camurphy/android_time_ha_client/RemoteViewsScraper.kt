package com.camurphy.android_time_ha_client

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Text recovered from a notification's layouts, plus why any source came back empty. */
data class ScrapeResult(
    val texts: List<String>,
    val diagnostics: List<String>,
)

/**
 * Reads the text out of a notification's layout.
 *
 * Some builds set no standard title/text extras at all and keep everything in the layout.
 * Custom layouts arrive as RemoteViews on the notification; standard templates have none,
 * and have to be rebuilt with [Notification.Builder.recoverBuilder] before they render
 * anything. Both are tried, and anything that fails is reported rather than swallowed —
 * a silent empty result is indistinguishable from a notification that genuinely has no
 * text, which wastes a debugging round trip on a device you cannot attach to.
 */
object RemoteViewsScraper {

    fun scrape(context: Context, notification: Notification): ScrapeResult {
        val texts = LinkedHashSet<String>()
        val diagnostics = mutableListOf<String>()

        collectFrom(context, "contentView", notification.contentView, texts, diagnostics)
        collectFrom(context, "bigContentView", notification.bigContentView, texts, diagnostics)
        collectFrom(context, "headsUpContentView", notification.headsUpContentView, texts, diagnostics)

        // Standard-template notifications carry no RemoteViews of their own; the system
        // builds them at render time, and this is the only way to see the result.
        runCatching {
            val builder = Notification.Builder.recoverBuilder(context, notification)
            collectFrom(context, "rebuilt.big", builder.createBigContentView(), texts, diagnostics)
            collectFrom(context, "rebuilt.content", builder.createContentView(), texts, diagnostics)
            collectFrom(context, "rebuilt.headsUp", builder.createHeadsUpContentView(), texts, diagnostics)
        }.onFailure {
            diagnostics += "recoverBuilder failed: ${it.javaClass.simpleName}: ${it.message}"
        }

        return ScrapeResult(texts.toList(), diagnostics)
    }

    private fun collectFrom(
        context: Context,
        label: String,
        views: android.widget.RemoteViews?,
        out: MutableSet<String>,
        diagnostics: MutableList<String>,
    ) {
        if (views == null) {
            diagnostics += "$label: null"
            return
        }
        val before = out.size
        runCatching {
            val inflated = views.apply(context, FrameLayout(context))
            collect(inflated, out)
        }.onFailure {
            diagnostics += "$label: inflate failed: ${it.javaClass.simpleName}: ${it.message}"
            return
        }
        diagnostics += "$label: ${out.size - before} new string(s)"
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
