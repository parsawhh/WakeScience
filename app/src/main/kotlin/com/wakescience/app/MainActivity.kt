package com.wakescience.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var tvWatchStatus: TextView
    private lateinit var tvLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvWatchStatus = findViewById(R.id.tvWatchStatus)
        tvLogs = findViewById(R.id.tvLogs)

        findViewById<Button>(R.id.btnRefreshWatches).setOnClickListener {
            refreshNodes()
        }

        findViewById<Button>(R.id.btnSendStartWindow).setOnClickListener {
            val now = System.currentTimeMillis()
            val deadline = now + (30 * 60 * 1000) // 30 mins
            sendMessageToWatch("/wakescience/start", now, deadline, "Started 30m Smart Window")
        }

        findViewById<Button>(R.id.btnSendTestWake).setOnClickListener {
            val now = System.currentTimeMillis()
            val deadline = now + 5000 // 5 seconds
            sendMessageToWatch("/wakescience/start", now, deadline, "Started 5s Test Alarm (Instant Wake)")
        }

        findViewById<Button>(R.id.btnSendStop).setOnClickListener {
            sendStopMessage()
        }

        refreshNodes()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLogs.append("[$time] $message\n")
    }

    private fun refreshNodes() {
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val nodes = withContext(Dispatchers.IO) {
                    Tasks.await(nodeClient.connectedNodes)
                }
                if (nodes.isEmpty()) {
                    tvWatchStatus.text = "No watches connected via Bluetooth"
                    log("Scan: 0 watches found.")
                } else {
                    val names = nodes.joinToString(", ") { "${it.displayName} (${it.id.take(6)}...)" }
                    tvWatchStatus.text = "Connected: $names"
                    log("Found watches: $names")
                }
            } catch (e: Exception) {
                tvWatchStatus.text = "Error scanning: ${e.message}"
                log("Scan error: ${e.message}")
            }
        }
    }

    private fun sendMessageToWatch(path: String, startMs: Long, deadlineMs: Long, label: String) {
        scope.launch {
            try {
                val payload = ByteBuffer.allocate(16).apply {
                    putLong(startMs)
                    putLong(deadlineMs)
                }.array()

                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val messageClient = Wearable.getMessageClient(this@MainActivity)
                val nodes = withContext(Dispatchers.IO) {
                    Tasks.await(nodeClient.connectedNodes)
                }

                if (nodes.isEmpty()) {
                    log("Cannot send: No paired watch found.")
                    return@launch
                }

                nodes.forEach { node ->
                    withContext(Dispatchers.IO) {
                        Tasks.await(messageClient.sendMessage(node.id, path, payload))
                    }
                    log("Sent $label to ${node.displayName}")
                }
            } catch (e: Exception) {
                log("Failed to send: ${e.message}")
            }
        }
    }

    private fun sendStopMessage() {
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val messageClient = Wearable.getMessageClient(this@MainActivity)
                val nodes = withContext(Dispatchers.IO) {
                    Tasks.await(nodeClient.connectedNodes)
                }

                if (nodes.isEmpty()) {
                    log("Cannot send stop: No paired watch.")
                    return@launch
                }

                nodes.forEach { node ->
                    withContext(Dispatchers.IO) {
                        Tasks.await(messageClient.sendMessage(node.id, "/wakescience/stop", ByteArray(0)))
                    }
                    log("Sent stop to ${node.displayName}")
                }
            } catch (e: Exception) {
                log("Failed to send stop: ${e.message}")
            }
        }
    }
}
