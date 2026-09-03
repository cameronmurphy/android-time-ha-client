package com.camurphy.ha_android_timer_bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.camurphy.ha_android_timer_bridge.ui.MainScreen
import com.camurphy.ha_android_timer_bridge.ui.ScreenActions
import com.camurphy.ha_android_timer_bridge.ui.theme.TimeHaClientTheme

/**
 * Setup and diagnostics.
 *
 * Two things have to be true for the bridge to work: Home Assistant has paired with this
 * tablet, and Android has granted notification access. The log at the bottom is the tuning
 * tool — timer notifications differ between builds, so you look at what actually arrived.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * Android 17 gates the local network, and mDNS registration with it. Ask on first run:
     * without it Home Assistant can never discover the tablet, and the failure is otherwise
     * invisible — the pairing server still answers, it just cannot be found.
     */
    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.localNetworkGranted()
            else viewModel.refresh()
        }

    private fun askForLocalNetwork() {
        if (LocalNetwork.granted(this)) return
        requestLocalNetwork.launch(LocalNetwork.PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The listener service usually primes these, but the activity can be the first thing
        // to run — on a fresh install, before notification access has been granted.
        SettingsStore.start(this)
        PairingStore.start(this)
        askForLocalNetwork()
        enableEdgeToEdge()
        setContent {
            TimeHaClientTheme {
                MainScreen(
                    viewModel = viewModel,
                    actions = ScreenActions(
                        openNotificationAccessSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        openBatterySettings = {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        requestLocalNetworkAccess = { askForLocalNetwork() },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Notification access and the battery exemption can change while we are backgrounded.
        viewModel.refresh()
    }
}
