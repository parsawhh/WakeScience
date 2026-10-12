package com.wakescience.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.wakescience.core.BridgeProtocol

class WearCommandListener : WearableListenerService() {

    companion object {
        private const val TAG = "WearCommandListener"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "onMessageReceived path: ${messageEvent.path}")
        when (messageEvent.path) {
            BridgeProtocol.PATH_CONFIG_SYNC -> {
                val config = BridgeProtocol.parseConfig(messageEvent.data)
                WearTrackingService.currentConfig = config
                Log.i(TAG, "Synchronized config: profile=${config.vibrationProfile.name}, window=${config.windowMinutes}m, ramp=${config.rampDurationSeconds}s")
            }
            BridgeProtocol.PATH_START -> {
                try {
                    val config = BridgeProtocol.parseConfig(messageEvent.data)
                    WearTrackingService.currentConfig = config

                    val now = System.currentTimeMillis()
                    val windowStart = now
                    val hardDeadline = now + (config.windowMinutes * 60 * 1000L)

                    val intent = Intent(this, WearTrackingService::class.java).apply {
                        putExtra("WINDOW_START_MS", windowStart)
                        putExtra("HARD_DEADLINE_MS", hardDeadline)
                        putExtra("CONFIG_BYTES", messageEvent.data)
                    }
                    startForegroundService(intent)
                    Log.i(TAG, "Successfully invoked startForegroundService for WearTrackingService")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start WearTrackingService: ${e.message}", e)
                }
            }
            BridgeProtocol.PATH_STOP -> {
                try {
                    val intent = Intent(this, WearTrackingService::class.java)
                    stopService(intent)
                    Log.i(TAG, "Successfully invoked stopService for WearTrackingService")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop WearTrackingService: ${e.message}", e)
                }
            }
        }
    }
}
