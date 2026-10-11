package com.wakescience.wear

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

/**
 * Listens for tracking window commands from the paired Phone app or Sleep as Android.
 */
class WearCommandListener : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/wakescience/start" -> {
                val buffer = ByteBuffer.wrap(messageEvent.data)
                val windowStart = buffer.long
                val hardDeadline = buffer.long

                val intent = Intent(this, WearTrackingService::class.java).apply {
                    putExtra("WINDOW_START_MS", windowStart)
                    putExtra("HARD_DEADLINE_MS", hardDeadline)
                }
                startForegroundService(intent)
            }
            "/wakescience/stop" -> {
                val intent = Intent(this, WearTrackingService::class.java)
                stopService(intent)
            }
        }
    }
}
