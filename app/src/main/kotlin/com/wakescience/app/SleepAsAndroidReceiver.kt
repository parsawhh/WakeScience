package com.wakescience.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Receives intents from Sleep as Android and bridges the smart wake window
 * to the Wear OS watch service via the Google Play Wearable Data Layer API.
 */
class SleepAsAndroidReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scope = CoroutineScope(Dispatchers.IO)
        
        when (intent.action) {
            "com.urbandroid.sleep.alarmclock.SMART_PERIOD_AUTO" -> {
                // Sleep as Android entered the smart wake window (e.g. 30 min before deadline)
                val windowStart = System.currentTimeMillis()
                val hardDeadline = intent.getLongExtra("TIMESTAMP", windowStart + (30 * 60 * 1000))

                val payload = ByteBuffer.allocate(16).apply {
                    putLong(windowStart)
                    putLong(hardDeadline)
                }.array()

                scope.launch {
                    val nodeClient = Wearable.getNodeClient(context)
                    val messageClient = Wearable.getMessageClient(context)
                    val nodes = nodeClient.connectedNodes.await()
                    nodes.forEach { node ->
                        messageClient.sendMessage(node.id, "/wakescience/start", payload)
                    }
                }
            }

            "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STOPPED_AUTO" -> {
                scope.launch {
                    val nodeClient = Wearable.getNodeClient(context)
                    val messageClient = Wearable.getMessageClient(context)
                    val nodes = nodeClient.connectedNodes.await()
                    nodes.forEach { node ->
                        messageClient.sendMessage(node.id, "/wakescience/stop", ByteArray(0))
                    }
                }
            }
        }
    }
}
