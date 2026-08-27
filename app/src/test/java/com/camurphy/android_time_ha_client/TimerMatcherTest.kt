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
}
