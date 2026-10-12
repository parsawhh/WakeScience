package com.wakescience.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.wakescience.core.BridgeProtocol
import com.wakescience.core.WakeEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Official Sleep as Android Wearable API Provider & Automation Receiver.
 * Spec: https://sleep.urbandroid.org/docs/devs/wearable_api.html
 *
 * Handles incoming control intents from Sleep as Android:
 * - CHECK_CONNECTED -> Replies with CONFIRM_CONNECTED
 * - START_TRACKING / SMART_PERIOD_AUTO -> Commands watch service to start tracking
 * - STOP_TRACKING / SLEEP_TRACKING_STOPPED_AUTO -> Commands watch service to stop tracking
 * - SET_PAUSE / SET_SUSPENDED -> Can pause tracking state
 */
class SleepAsAndroidReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SleepAsAndroidReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received broadcast action: $action")
        val scope = CoroutineScope(Dispatchers.IO)

        when (action) {
            // Handshake ping from Sleep as Android
            SleepAsAndroidProvider.ACTION_CHECK_CONNECTED -> {
                SleepAsAndroidProvider.confirmConnected(context)
            }

            // Start tracking command from Sleep as Android or automated smart period
            SleepAsAndroidProvider.ACTION_START_TRACKING,
            "com.urbandroid.sleep.alarmclock.SMART_PERIOD_AUTO" -> {
                val now = System.currentTimeMillis()
                val hardDeadline = intent.getLongExtra("TIMESTAMP", now + (30 * 60 * 1000L))
                val windowDurationMs = ((hardDeadline - now).coerceAtLeast(5 * 60 * 1000L))
                val windowMinutes = (windowDurationMs / (60 * 1000L)).toInt().coerceIn(5, 120)

                val config = WakeEngineConfig(
                    alarmEpochMs = hardDeadline,
                    windowMinutes = windowMinutes
                )
                val payload = BridgeProtocol.serializeConfig(config)

                scope.launch {
                    try {
                        val nodeClient = Wearable.getNodeClient(context)
                        val messageClient = Wearable.getMessageClient(context)
                        val nodes = Tasks.await(nodeClient.connectedNodes)
                        nodes.forEach { node ->
                            messageClient.sendMessage(node.id, BridgeProtocol.PATH_START, payload)
                        }
                        Log.i(TAG, "Forwarded START to ${nodes.size} watch(es) for deadline: $hardDeadline")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dispatch START to watch: ${e.message}", e)
                    }
                }
            }

            // Stop tracking command from Sleep as Android
            SleepAsAndroidProvider.ACTION_STOP_TRACKING,
            "com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STOPPED_AUTO" -> {
                scope.launch {
                    try {
                        val nodeClient = Wearable.getNodeClient(context)
                        val messageClient = Wearable.getMessageClient(context)
                        val nodes = Tasks.await(nodeClient.connectedNodes)
                        nodes.forEach { node ->
                            messageClient.sendMessage(node.id, BridgeProtocol.PATH_STOP, ByteArray(0))
                        }
                        Log.i(TAG, "Forwarded STOP to ${nodes.size} watch(es)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dispatch STOP to watch: ${e.message}", e)
                    }
                }
            }
        }
    }
}
