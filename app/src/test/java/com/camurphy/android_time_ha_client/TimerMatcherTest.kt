package com.camurphy.android_time_ha_client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fixtures marked "observed" are exactly what Google Clock posted on an Android 15
 * Pixel Tablet image — note that the standard title/text extras are all null, which is why
 * the matcher leans on the custom layout's text.
 */
class TimerMatcherTest {

    private fun snap(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        category: String? = null,
        fullScreen: Boolean = false,
        ongoing: Boolean = false,
        chronometer: Boolean = false,
        channelId: String? = "timers",
        actions: List<String> = emptyList(),
        viewTexts: List<String> = emptyList(),
        metricsLabel: String? = null,
        appLabel: String? = null,
        extraTexts: List<String> = emptyList(),
        pkg: String = "com.google.android.deskclock",
    ) = NotificationSnapshot(
        packageName = pkg,
        channelId = channelId,
        category = category,
        title = title,
        text = text,
        bigText = bigText,
        hasFullScreenIntent = fullScreen,
        isOngoing = ongoing,
        showsChronometer = chronometer,
        actionTitles = actions,
        viewTexts = viewTexts,
        metricsLabel = metricsLabel,
        appLabel = appLabel,
        extraTexts = extraTexts,
    )

    // ---------------------------------------------------------------- observed fixtures

    @Test
    fun `observed - google clock firing timer is a named timer`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Firing",
                category = "alarm",
                fullScreen = true,
                ongoing = true,
                viewTexts = listOf("00:00", "Pasta"),
            )
        )
        assertNotNull(match)
        assertEquals(EventKind.TIMER, match!!.kind)
        assertEquals("Pasta", match.timerName)
    }

    @Test
    fun `observed - google clock running timer is ignored`() {
        val match = TimerMatcher.classify(
            snap(channelId = "Timers", viewTexts = listOf("00:05", "Pasta"))
        )
        assertNull(match)
    }

    @Test
    fun `observed - several expired timers fire without a name`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Firing",
                category = "alarm",
                fullScreen = true,
                ongoing = true,
                viewTexts = listOf("−01:53", "2 timers expired"),
            )
        )
        assertNotNull(match)
        assertEquals(EventKind.TIMER, match!!.kind)
        assertNull(match.timerName)
    }

    // ---------------------------------------------------------------- alarms vs timers

    @Test
    fun `a snooze button makes it an alarm`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Firing",
                category = "alarm",
                fullScreen = true,
                actions = listOf("Snooze", "Dismiss"),
                viewTexts = listOf("7:00 AM", "Wake up"),
            )
        )
        assertNotNull(match)
        assertEquals(EventKind.ALARM, match!!.kind)
        assertEquals("Wake up", match.timerName)
    }

    @Test
    fun `an add-one-minute button makes it a timer`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Firing",
                category = "alarm",
                fullScreen = true,
                actions = listOf("Stop", "Add 1 min"),
                viewTexts = listOf("00:00", "Rice"),
            )
        )
        assertEquals(EventKind.TIMER, match?.kind)
        assertEquals("Rice", match?.timerName)
    }

    @Test
    fun `a time of day with no buttons reads as an alarm`() {
        val match = TimerMatcher.classify(
            snap(channelId = "Firing", category = "alarm", fullScreen = true, viewTexts = listOf("6:30 AM"))
        )
        assertEquals(EventKind.ALARM, match?.kind)
    }

    // ---------------------------------------------------------------- text-based devices

    @Test
    fun `named timer with plain text extras`() {
        val match = TimerMatcher.classify(
            snap(title = "Pasta", text = "Timer complete", category = "alarm", fullScreen = true)
        )
        assertEquals(EventKind.TIMER, match?.kind)
        assertEquals("Pasta", match?.timerName)
    }

    @Test
    fun `label carrying the word timer keeps only the label`() {
        val match = TimerMatcher.classify(snap(title = "Pasta timer", text = "Time's up", category = "alarm"))
        assertEquals("Pasta", match?.timerName)
    }

    @Test
    fun `running countdown is ignored`() {
        assertNull(TimerMatcher.classify(snap(title = "Timer", text = "0:42", ongoing = true, chronometer = true)))
    }

    @Test
    fun `unnamed timer fires with a null name`() {
        val match = TimerMatcher.classify(snap(title = "Timer", text = "Time's up", category = "alarm"))
        assertNotNull(match)
        assertNull(match!!.timerName)
    }

    @Test
    fun `duration is reported separately rather than used as a name`() {
        val match = TimerMatcher.classify(snap(title = "10 min timer", text = "Time's up", category = "alarm"))
        assertNotNull(match)
        assertNull(match!!.timerName)
        assertEquals("10 min", match.duration)
    }

    @Test
    fun `name is taken from big text when the title is generic`() {
        val match = TimerMatcher.classify(
            snap(title = "Timer", bigText = "Sourdough - time's up", category = "alarm")
        )
        assertEquals("Sourdough", match?.timerName)
    }

    @Test
    fun `unrelated notifications are ignored`() {
        assertNull(TimerMatcher.classify(snap(title = "Weather", text = "Rain later", channelId = "forecast")))
        assertNull(TimerMatcher.classify(snap(title = "3 new emails", channelId = "mail")))
    }

    @Test
    fun `forward everything overrides the heuristics`() {
        val match = TimerMatcher.classify(snap(title = "Anything at all", channelId = "misc"), forwardEverything = true)
        assertNotNull(match)
        assertEquals("Anything at all", match!!.timerName)
    }

    @Test
    fun `tap-to-stop boilerplate is stripped from the name`() {
        val match = TimerMatcher.classify(
            snap(title = "Rice timer", text = "Time's up. Tap to stop the timer", category = "alarm")
        )
        assertEquals("Rice", match?.timerName)
    }

    // ------------------------------------------------- observed: Hub Mode, real tablet

    @Test
    fun `observed - hub mode firing timer has no full screen intent and still fires`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Timers v2",
                category = "alarm",
                fullScreen = false,
                ongoing = true,
                actions = listOf("Stop", "Add 1 min"),
                viewTexts = listOf("0:00", "Pasta"),
            )
        )
        assertNotNull(match)
        assertEquals(EventKind.TIMER, match!!.kind)
        assertEquals("Pasta", match.timerName)
    }

    @Test
    fun `observed - hub mode firing timer fires even when nothing could be scraped`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Timers v2",
                category = "alarm",
                fullScreen = false,
                ongoing = true,
                actions = listOf("Stop", "Add 1 min"),
                viewTexts = emptyList(),
            )
        )
        assertNotNull(match)
        assertEquals(EventKind.TIMER, match!!.kind)
    }

    @Test
    fun `a pausable countdown is still running, even on an alarm category`() {
        assertNull(
            TimerMatcher.classify(
                snap(
                    channelId = "Timers v2",
                    category = "alarm",
                    ongoing = true,
                    actions = listOf("Pause", "Add 1 min"),
                    viewTexts = listOf("0:09", "Pasta"),
                )
            )
        )
    }

    @Test
    fun `regression - a clock face must not veto a timer that is ringing`() {
        // 1.2 scraped the layout successfully for the first time, saw a clock, and
        // suppressed every real timer. A stop button with no pause button outranks it.
        val match = TimerMatcher.classify(
            snap(
                channelId = "Timers v2",
                category = "alarm",
                fullScreen = false,
                actions = listOf("Stop", "Add 1 min"),
                viewTexts = listOf("-00:14", "Bread"),
            )
        )
        assertNotNull(match)
        assertEquals("Bread", match!!.timerName)
    }

    // ------------------------------------- observed: "create a testing timer for 10 seconds"

    /** The exact strings the Pixel Tablet sent for an Assistant-created timer. */
    private fun hubModeTimer(metricsLabel: String?) = snap(
        channelId = "Firing",
        category = "alarm",
        fullScreen = false,
        ongoing = true,
        actions = listOf("Stop", "Add 1 min"),
        viewTexts = listOf("\u2022", "Clock", "testing", "\u221200:01", "testing:", "00:00"),
        metricsLabel = metricsLabel,
        appLabel = "Clock",
    )

    @Test
    fun `observed - metric style label is used verbatim`() {
        val match = TimerMatcher.classify(hubModeTimer("testing"))
        assertNotNull(match)
        assertEquals("testing", match!!.timerName)
        assertEquals("metricsLabel", match.nameSource)
    }

    @Test
    fun `observed - falls back to layout text without picking the app name`() {
        // Older builds have no android.metrics; "Clock" is the notification header, and
        // picking it was the bug that reported every timer as "Clock".
        val match = TimerMatcher.classify(hubModeTimer(null))
        assertNotNull(match)
        assertEquals("testing", match!!.timerName)
    }

    @Test
    fun `the posting app name is never the timer name`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Firing",
                category = "alarm",
                actions = listOf("Stop"),
                viewTexts = listOf("Clock", "00:00"),
                appLabel = "Clock",
            )
        )
        assertNotNull(match)
        assertNull(match!!.timerName)
    }

    // ---------------------------------------------------------------- timer length

    @Test
    fun `clock faces parse to seconds`() {
        assertEquals(9L, TimerMatcher.parseClock("0:09"))
        assertEquals(600L, TimerMatcher.parseClock("10:00"))
        assertEquals(3720L, TimerMatcher.parseClock("1:02:00"))
        // The count-up shown once a timer has gone off.
        assertEquals(1L, TimerMatcher.parseClock("\u221200:01"))
        assertNull(TimerMatcher.parseClock("Pasta"))
        assertNull(TimerMatcher.parseClock("7:00 AM"))
    }

    @Test
    fun `durations round up, because they are measured just after the timer starts`() {
        // A ten second timer is first seen with about 9.8s left.
        assertEquals("10 seconds", TimerMatcher.humanDuration(9_800))
        assertEquals("10 minutes", TimerMatcher.humanDuration(599_500))
        assertEquals("1 second", TimerMatcher.humanDuration(1_000))
        assertEquals("1 hour", TimerMatcher.humanDuration(3_600_000))
        assertEquals("1 hour 1 minute", TimerMatcher.humanDuration(3_660_000))
        assertNull(TimerMatcher.humanDuration(0))
    }

    @Test
    fun `a nearly-whole minute is treated as that minute`() {
        // Measurement lag, not a genuine 58 second timer.
        assertEquals("1 minute", TimerMatcher.humanDuration(58_200))
    }

    // ------------------------------------------------- observed: unnamed Hub Mode timer

    @Test
    fun `observed - an unnamed hub mode timer reports no name`() {
        // Clock labels an unnamed timer "Time's up". That is boilerplate, not a name, and
        // reporting anything here produced "android.app.Notification${'$'}MetricStyle timer
        // finished" on both phones.
        val match = TimerMatcher.classify(
            snap(
                channelId = "Timers v2",
                category = "alarm",
                ongoing = true,
                actions = listOf("Stop", "Add 1 min"),
                viewTexts = listOf("\u2022", "Clock", "Time's up", "\u221200:01", "Time's up:", "00:00"),
                metricsLabel = "Time's up",
                appLabel = "Clock",
                extraTexts = listOf(
                    "android.app.Notification${'$'}MetricStyle",
                    "androidx.core.app.NotificationCompat${'$'}MetricStyle",
                ),
            )
        )
        assertNotNull(match)
        assertEquals(EventKind.TIMER, match!!.kind)
        assertNull(match.timerName)
    }

    @Test
    fun `class names are never used as a timer name`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Firing",
                category = "alarm",
                actions = listOf("Stop"),
                viewTexts = listOf("com.example.some.Thing${'$'}Style"),
            )
        )
        assertNotNull(match)
        assertNull(match!!.timerName)
    }

    @Test
    fun `a genuinely named hub mode timer still works`() {
        val match = TimerMatcher.classify(
            snap(
                channelId = "Timers v2",
                category = "alarm",
                actions = listOf("Stop", "Add 1 min"),
                viewTexts = listOf("\u2022", "Clock", "pasta", "00:00"),
                metricsLabel = "pasta",
                appLabel = "Clock",
            )
        )
        assertEquals("pasta", match?.timerName)
        assertEquals("metricsLabel", match?.nameSource)
    }
}
