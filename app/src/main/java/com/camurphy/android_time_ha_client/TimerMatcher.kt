package com.camurphy.android_time_ha_client

/** What a firing notification turned out to be. */
enum class EventKind(val wireName: String) {
    TIMER("timer_finished"),
    ALARM("alarm_fired"),
}

/**
 * Everything we scrape out of a posted notification. Kept free of Android types so the
 * matching rules can be exercised by plain JVM unit tests.
 */
data class NotificationSnapshot(
    val packageName: String,
    val channelId: String? = null,
    val category: String? = null,
    val title: String? = null,
    val titleBig: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val infoText: String? = null,
    val summaryText: String? = null,
    val hasFullScreenIntent: Boolean = false,
    val isOngoing: Boolean = false,
    val showsChronometer: Boolean = false,
    val postTimeMs: Long = 0L,
    val notificationId: Int = 0,
    val tag: String? = null,
    /** Titles of the notification's buttons — "Snooze" and "Stop" tell alarms from timers. */
    val actionTitles: List<String> = emptyList(),
    /** Text recovered from a custom notification layout, when the extras were empty. */
    val viewTexts: List<String> = emptyList(),
    /** CharSequence values from extras keys we do not read by name. */
    val extraTexts: List<String> = emptyList(),
    /** Every extras key and value, for working out where a device hides the label. */
    val extrasDump: List<String> = emptyList(),
    /** Why each notification layout yielded what it did. */
    val scrapeDiagnostics: List<String> = emptyList(),
    val tickerText: String? = null,
    /**
     * Label from a MetricStyle notification's `android.metrics` bundle. Newer Clock builds
     * put the timer's name here as a real field, which beats scraping rendered text.
     */
    val metricsLabel: String? = null,
    /** Display name of the posting app, so its header row is not mistaken for the label. */
    val appLabel: String? = null,
)

/** A firing alarm or timer we intend to report. */
data class TimerMatch(
    val kind: EventKind,
    /** Best guess at the label, or null when there was none to find. */
    val timerName: String?,
    /** Which field [timerName] came from — useful when tuning the rules. */
    val nameSource: String?,
    /** Duration text ("10 min") when that is all the notification gave us. */
    val duration: String?,
    /** Why we decided this had fired. */
    val reason: String,
    /** Why we called it a timer rather than an alarm. */
    val kindReason: String,
)

/**
 * Decides whether a notification is a timer or alarm going off, and what it was called.
 *
 * The rules are shaped by what Google Clock actually posts. Its firing notification sets no
 * standard text extras at all — title, text and bigText are null — and puts everything in a
 * custom layout, so [NotificationSnapshot.viewTexts] carries the real content. Both alarms
 * and timers arrive on the same "Firing" channel with category=alarm, so the button titles
 * are what separates them.
 */
object TimerMatcher {

    private val TIMER_WORD = Regex("""\btimers?\b""", RegexOption.IGNORE_CASE)
    private val TIMES_UP = Regex("""\btime'?s\s+up\b""", RegexOption.IGNORE_CASE)
    private val ALARM_WORD = Regex("""\balarms?\b""", RegexOption.IGNORE_CASE)

    /** Channels Google Clock uses when something is actually ringing. */
    private val FIRING_CHANNELS = setOf("firing", "firing_alarms", "firing_timers")

    private val DONE_PHRASES = listOf(
        "time's up", "times up", "timer complete", "timer completed", "timer finished",
        "timer done", "timer is up", "timer's up", "timer expired", "timers expired",
        "timer elapsed", "is up", "complete", "completed", "finished", "expired",
        "elapsed", "done",
    )

    /** Buttons that only ever appear on a ringing alarm. */
    private val ALARM_ACTIONS = listOf("snooze")

    /** Buttons that only ever appear on a ringing timer. */
    private val TIMER_ACTIONS = listOf("add 1 min", "add one minute", "+1 min", "1 min", "reset")

    private val BOILERPLATE = listOf(
        Regex("""\btime'?s\s+up\b""", RegexOption.IGNORE_CASE),
        Regex("""\btimers?\s+(is\s+|are\s+)?(complete(d)?|finished|done|up|expired|elapsed)\b""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\d+\s+timers?\b""", RegexOption.IGNORE_CASE),
        Regex("""\btimers?\b""", RegexOption.IGNORE_CASE),
        Regex("""\balarms?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(complete(d)?|finished|expired|elapsed|ringing|done)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(tap|swipe|touch)\s+to\s+\w+.*$""", RegexOption.IGNORE_CASE),
    )

    // Units longest-first so "min" is not consumed as a bare "m".
    private val DURATION_ONLY = Regex(
        """^(\d+\s*(hours|hour|hrs|hr|h|minutes|minute|mins|min|m|seconds|second|secs|sec|s)\s*)+$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER_ONLY = Regex("""^\d+$""")

    /** A running or overrun countdown: "00:05", "−01:53", "1:02:00". */
    private val COUNTDOWN = Regex("""^[−–—-]?\d{1,3}:\d{2}(:\d{2})?$""")

    /** A wall-clock time, which means an alarm rather than a timer: "7:00 AM". */
    private val WALL_CLOCK = Regex("""^\d{1,2}:\d{2}\s*(am|pm)$""", RegexOption.IGNORE_CASE)

    private val SEPARATORS_ONLY = Regex("""^[\s:•\-–—,.·|]*$""")

    /**
     * @param forwardEverything skip the "did something fire" test and report anything from
     *   the package. The escape hatch for a device posting something unrecognised.
     */
    fun classify(s: NotificationSnapshot, forwardEverything: Boolean = false): TimerMatch? {
        val textFields = listOfNotNull(
            s.title, s.titleBig, s.text, s.bigText, s.subText, s.infoText, s.summaryText,
        ) + s.viewTexts
        val haystack = (textFields + listOfNotNull(s.channelId) + s.actionTitles)
            .joinToString(" ")
            .lowercase()

        val channel = s.channelId?.lowercase().orEmpty()
        val firingChannel = channel in FIRING_CHANNELS || channel.contains("firing")
        val donePhrase = DONE_PHRASES.firstOrNull { haystack.contains(it) }

        // The buttons are the most reliable signal across builds: a timer that is still
        // counting down can be paused, one that is ringing can only be stopped. This holds
        // where channel names and full-screen intents do not — Hub Mode posts its firing
        // timer on a "Timers v2" channel with no full-screen intent at all.
        val actions = s.actionTitles.joinToString(" ").lowercase()
        val canStop = actions.contains("stop") || actions.contains("dismiss")
        val canPause = actions.contains("pause")

        // Anything that looks like it is still counting down, unless a fired signal says
        // otherwise. Note a ringing timer often shows a clock too, counting up from zero,
        // so a clock face alone must never veto.
        val stillCounting = canPause || s.showsChronometer || s.viewTexts.any { COUNTDOWN.matches(it) }

        val reason = when {
            s.hasFullScreenIntent -> "full-screen intent (something is ringing)"
            donePhrase != null -> "matched completion phrase \"$donePhrase\""
            canStop && !canPause -> "offers \"stop\" but not \"pause\", so it is ringing"
            firingChannel && !stillCounting -> "posted on the \"${s.channelId}\" channel"
            s.category == "alarm" && !stillCounting -> "category=alarm"
            forwardEverything -> "forward-everything enabled for ${s.packageName}"
            else -> return null
        }

        // Only report things that are plausibly a clock event at all.
        val looksRelevant = firingChannel || s.hasFullScreenIntent || s.category == "alarm" ||
            TIMER_WORD.containsMatchIn(haystack) || TIMES_UP.containsMatchIn(haystack)
        if (!looksRelevant && !forwardEverything) return null

        val (kind, kindReason) = classifyKind(s, haystack)
        val (name, source, duration) = extractName(s)
        return TimerMatch(
            kind = kind,
            timerName = name,
            nameSource = source,
            duration = duration,
            reason = reason,
            kindReason = kindReason,
        )
    }

    /**
     * Alarm or timer? Google Clock puts both on the same channel with the same category, so
     * the buttons decide it: a ringing alarm offers Snooze, a ringing timer does not.
     */
    private fun classifyKind(s: NotificationSnapshot, haystack: String): Pair<EventKind, String> {
        val actions = s.actionTitles.joinToString(" ").lowercase()

        ALARM_ACTIONS.firstOrNull { actions.contains(it) }?.let {
            return EventKind.ALARM to "notification offers a \"$it\" button"
        }
        TIMER_ACTIONS.firstOrNull { actions.contains(it) }?.let {
            return EventKind.TIMER to "notification offers a \"$it\" button"
        }
        if (s.channelId?.lowercase()?.contains("timer") == true) {
            return EventKind.TIMER to "channel \"${s.channelId}\" names timers"
        }
        if (s.channelId?.lowercase()?.contains("alarm") == true) {
            return EventKind.ALARM to "channel \"${s.channelId}\" names alarms"
        }
        // A count-up or count-down clock is a timer; a wall-clock time is an alarm.
        if (s.viewTexts.any { COUNTDOWN.matches(it) && !WALL_CLOCK.matches(it) }) {
            return EventKind.TIMER to "shows a countdown rather than a time of day"
        }
        if (s.viewTexts.any { WALL_CLOCK.matches(it) }) {
            return EventKind.ALARM to "shows a time of day"
        }
        if (TIMER_WORD.containsMatchIn(haystack) || TIMES_UP.containsMatchIn(haystack)) {
            return EventKind.TIMER to "text mentions a timer"
        }
        if (ALARM_WORD.containsMatchIn(haystack)) {
            return EventKind.ALARM to "text mentions an alarm"
        }
        return EventKind.TIMER to "no alarm signals found, assuming a timer"
    }

    /**
     * Pull the label out. Fields are tried in the order that put the label first on the
     * devices tested; anything that reduces to a bare duration, clock time or count is kept
     * as [TimerMatch.duration] or skipped rather than returned as a name.
     */
    fun extractName(s: NotificationSnapshot): Triple<String?, String?, String?> {
        val candidates = listOf(
            "metricsLabel" to s.metricsLabel,
            "title" to s.title,
            "titleBig" to s.titleBig,
            "bigText" to s.bigText,
            "text" to s.text,
            "subText" to s.subText,
            "summaryText" to s.summaryText,
            "infoText" to s.infoText,
        ) + s.viewTexts.map { "viewText" to it } +
            listOf("tickerText" to s.tickerText) +
            s.extraTexts.map { "extra" to it }

        var duration: String? = null
        for ((field, raw) in candidates) {
            if (raw == null) continue
            val trimmed = raw.trim()
            // A rebuilt notification includes its header, so the app's own name shows up as
            // the first piece of text. It is never the timer's label.
            if (s.appLabel != null && trimmed.equals(s.appLabel, ignoreCase = true)) continue
            // Clock faces and countdowns are never the name.
            if (COUNTDOWN.matches(trimmed) || WALL_CLOCK.matches(trimmed)) continue
            val cleaned = clean(trimmed)
            if (cleaned.isEmpty()) continue
            // "2 timers expired" reduces to "2", which is a count, not a label.
            if (NUMBER_ONLY.matches(cleaned)) continue
            if (DURATION_ONLY.matches(cleaned)) {
                if (duration == null) duration = cleaned
                continue
            }
            return Triple(cleaned, field, duration)
        }
        return Triple(null, null, duration)
    }

    private fun clean(raw: String): String {
        var out = raw
        for (pattern in BOILERPLATE) out = pattern.replace(out, " ")
        out = out.replace(Regex("""\s+"""), " ").trim()
        out = out.trim(' ', ':', '•', '-', '–', '—', ',', '.', '·', '|')
        return if (SEPARATORS_ONLY.matches(out)) "" else out.trim()
    }
}
