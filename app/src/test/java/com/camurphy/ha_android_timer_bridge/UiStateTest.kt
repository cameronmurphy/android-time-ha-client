package com.camurphy.ha_android_timer_bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the form has anything worth saving.
 *
 * Settings is built with every field given explicitly: its default device name reads
 * android.os.Build, which is not available to a JVM test.
 */
class UiStateTest {

    private val stored = Settings(
        webhookUrl = "https://ha.example/hook",
        enabled = true,
        logAll = true,
        forwardEverything = false,
        deviceName = "Pixel Tablet",
        packagesRaw = "com.google.android.deskclock",
    )

    private val clean = UiState(
        deviceName = stored.deviceName,
        packages = stored.packagesRaw,
        webhookUrl = stored.webhookUrl,
        forwardingEnabled = stored.enabled,
        logAll = stored.logAll,
        forwardEverything = stored.forwardEverything,
        saved = stored,
    )

    @Test
    fun `a form matching what is stored has nothing to save`() {
        assertFalse(clean.unsavedChanges)
    }

    @Test
    fun `editing a text field is a change`() {
        assertTrue(clean.copy(deviceName = "Kitchen tablet").unsavedChanges)
        assertTrue(clean.copy(packages = "com.android.deskclock").unsavedChanges)
        assertTrue(clean.copy(webhookUrl = "https://ha.example/other").unsavedChanges)
    }

    @Test
    fun `flipping a switch is a change`() {
        assertTrue(clean.copy(forwardingEnabled = false).unsavedChanges)
        assertTrue(clean.copy(logAll = false).unsavedChanges)
        assertTrue(clean.copy(forwardEverything = true).unsavedChanges)
    }

    @Test
    fun `typing and undoing leaves nothing to save`() {
        val edited = clean.copy(deviceName = "something else")
        assertTrue(edited.unsavedChanges)
        assertFalse(edited.copy(deviceName = stored.deviceName).unsavedChanges)
    }

    @Test
    fun `state that is not part of the form is not a change`() {
        // Pairing, permissions and the log all move on their own; none of them is
        // something the Save button could write.
        assertFalse(clean.copy(paired = true, pairingCode = "111111").unsavedChanges)
        assertFalse(clean.copy(notificationAccess = false, listenerConnected = false).unsavedChanges)
        assertFalse(clean.copy(advertisedAs = "Pixel Tablet").unsavedChanges)
    }
}
