package com.camurphy.ha_android_timer_bridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.camurphy.ha_android_timer_bridge.ui.AccessSection
import com.camurphy.ha_android_timer_bridge.ui.PairingSection
import com.camurphy.ha_android_timer_bridge.ui.ScreenActions
import com.camurphy.ha_android_timer_bridge.ui.ScreenCallbacks
import com.camurphy.ha_android_timer_bridge.ui.theme.TimeHaClientTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The screen renders on a device, and its buttons reach the callbacks they are wired to. */
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = UiState(
        pairingCode = "123456",
        deviceId = "test-device",
        address = "127.0.0.1:8127",
        notificationAccess = true,
        listenerConnected = true,
    )

    @Test
    fun the_pairing_code_is_on_screen_for_someone_to_read_out() {
        compose.setContent {
            TimeHaClientTheme { PairingSection(state, ScreenCallbacks()) }
        }
        compose.onNodeWithText("123456").assertIsDisplayed()
    }

    @Test
    fun asking_for_a_new_code_reaches_the_callback() {
        var asked = false
        compose.setContent {
            TimeHaClientTheme {
                PairingSection(state, ScreenCallbacks(onNewPairingCode = { asked = true }))
            }
        }
        compose.onNodeWithText("New code").performClick()
        assertTrue("New code should reach the callback", asked)
    }

    @Test
    fun unpair_is_disabled_until_there_is_something_to_unpair() {
        var asked = false
        compose.setContent {
            TimeHaClientTheme {
                PairingSection(
                    state.copy(paired = false),
                    ScreenCallbacks(onUnpair = { asked = true }),
                )
            }
        }
        compose.onNodeWithText("Unpair").performClick()
        assertTrue("Unpair should do nothing while unpaired", !asked)
    }

    @Test
    fun the_access_section_offers_the_settings_screen_when_access_is_missing() {
        compose.setContent {
            TimeHaClientTheme {
                AccessSection(
                    state.copy(notificationAccess = false, listenerConnected = false),
                    ScreenActions(),
                )
            }
        }
        compose.onNodeWithText("Open notification access settings").assertIsDisplayed()
    }
}
