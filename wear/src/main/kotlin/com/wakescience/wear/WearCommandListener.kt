package com.wakescience.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

/**
 * Listens for tracking window commands from the paired Phone app or Sleep as Android.
 */
class WearCommandListener : WearableListenerService() {

    companion object {
        private const val TAG = "WearCommandListener"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "onMessageReceived path: ${messageEvent.path}")
        when (messageEvent.path) {
            "/wakescience/start" -> {
                try {
                    val buffer = ByteBuffer.wrap(messageEvent.data)
                    val windowStart = if (buffer.remaining() >= 8) buffer.long else System.currentTimeMillis()
                    val hardDeadline = if (buffer.remaining() >= 8) buffer.long else (System.currentTimeMillis() + 1800000L)

                    Log.i(TAG, "Parsed start payload: windowStart=$windowStart, hardDeadline=$hardDeadline")

                    val intent = Intent(this, WearTrackingService::class.java).apply {
                        putExtra("WINDOW_START_MS", windowStart)
                        putExtra("HARD_DEADLINE_MS", hardDeadline)
                    }
                    startForegroundService(intent)
                    Log.i(TAG, "Successfully invoked startForegroundService for WearTrackingService")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start WearTrackingService: ${e.message}", e)
                }
            }
            "/wakescience/stop" -> {
                try {
                    val intent = Intent(this, WearTrackingService::class.java)
                    stopService(intent)
                    Log.i(TAG, "Successfully invoked stopService for WearTrackingService")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop WearTrackingService: ${e.message}", e)
                }
            }
            else -> {
                Log.w(TAG, "Unhandled message path: ${messageEvent.path}")
            }
        }
    }
}
