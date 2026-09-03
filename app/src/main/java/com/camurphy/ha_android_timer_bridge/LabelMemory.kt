package com.camurphy.ha_android_timer_bridge

/**
 * The name of the timer that is currently running.
 *
 * Some builds put a timer's name on its running countdown but not on the notification that
 * fires, so the name has to be carried across. The subtlety is when to let go of it: Google
 * Clock reuses one notification id for every timer, so there is no key that separates one
 * timer from the next, and a name held too long gets attributed to a later timer that never
 * had one — an unnamed timer announcing itself as "chicken" because a chicken timer ran
 * earlier in the day.
 *
 * So this tracks the current timer rather than the last name seen. Every sighting of a
 * running countdown replaces what is held, *including* a sighting with no name at all: an
 * unnamed timer counting down is positive evidence that the name we were holding belongs to
 * a timer that is over. A name is also dropped once its timer has fired and been reported.
 */
class LabelMemory(private val ttlMs: Long) {
    private var held: Pair<String, Long>? = null

    /**
     * Record what a running countdown is called. A null label clears what is held, which is
     * the point: it means the timer now running has no name.
     */
    fun observeRunning(label: String?, nowMs: Long) {
        held = label?.let { it to nowMs }
    }

    /** The running timer's name, if one was seen recently enough to still be trusted. */
    fun recall(nowMs: Long): String? = held?.takeIf { nowMs - it.second < ttlMs }?.first

    /** Forget the name — its timer has fired and been reported. */
    fun clear() {
        held = null
    }
}
