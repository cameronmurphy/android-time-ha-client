package com.camurphy.ha_android_timer_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelMemoryTest {
    private val ttl = 4 * 60 * 60 * 1000L

    @Test
    fun `carries a name from the countdown to the notification that fires`() {
        val memory = LabelMemory(ttl)
        memory.observeRunning("chicken", 0L)
        assertEquals("chicken", memory.recall(60_000L))
    }

    @Test
    fun `an unnamed timer does not inherit the last timer's name`() {
        // The bug this class exists to prevent: a "chicken" timer runs and fires, then a
        // timer set with no name at all is announced as "chicken".
        val memory = LabelMemory(ttl)
        memory.observeRunning("chicken", 0L)
        memory.clear() // the chicken timer fired and was reported

        memory.observeRunning(null, 60_000L) // an unnamed timer is now counting down
        assertNull(memory.recall(120_000L))
    }

    @Test
    fun `a nameless countdown clears a name even if the last timer was never reported`() {
        val memory = LabelMemory(ttl)
        memory.observeRunning("chicken", 0L)
        memory.observeRunning(null, 60_000L)
        assertNull(memory.recall(120_000L))
    }

    @Test
    fun `a later named timer replaces the earlier name`() {
        val memory = LabelMemory(ttl)
        memory.observeRunning("chicken", 0L)
        memory.observeRunning("pasta", 60_000L)
        assertEquals("pasta", memory.recall(120_000L))
    }

    @Test
    fun `a name is forgotten once its timer has fired and been reported`() {
        val memory = LabelMemory(ttl)
        memory.observeRunning("chicken", 0L)
        memory.clear()
        assertNull(memory.recall(1_000L))
    }

    @Test
    fun `a name goes stale rather than lingering forever`() {
        val memory = LabelMemory(ttl)
        memory.observeRunning("chicken", 0L)
        assertNull(memory.recall(ttl + 1))
    }

    @Test
    fun `a long timer keeps its name for the whole countdown`() {
        val memory = LabelMemory(ttl)
        memory.observeRunning("slow roast", 0L)
        assertEquals("slow roast", memory.recall(ttl - 1))
    }
}
