package com.wakescience.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.AvailabilityException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
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
            val deadline = now + (30 * 60 * 1000)
            sendMessageToWatch("/wakescience/start", now, deadline, "Started 30m Smart Window")
        }

        findViewById<Button>(R.id.btnSendTestWake).setOnClickListener {
            val now = System.currentTimeMillis()
            val deadline = now + 5000
            sendMessageToWatch("/wakescience/start", now, deadline, "Started 5s Test Alarm (Instant Wake)")
        }

        findViewById<Button>(R.id.btnSendStop).setOnClickListener {
            sendStopMessage()
        }

        log("App started. Package: $packageName")
        refreshNodes()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLogs.append("[$time] $message\n")
    }

    private fun refreshNodes() {
        scope.launch {
            log("--- Refreshing Nodes ---")
            val nodeClient = Wearable.getNodeClient(this@MainActivity)
            try {
                val nodes: List<Node> = withContext(Dispatchers.IO) {
                    Tasks.await(nodeClient.connectedNodes)
                }
                if (nodes.isEmpty()) {
                    tvWatchStatus.text = "No watches connected via Wearable API"
                    log("connectedNodes: 0 nodes returned")
                } else {
                    val info = nodes.joinToString(", ") { "${it.displayName} (id: ${it.id.take(8)}... nearby=${it.isNearby})" }
                    tvWatchStatus.text = "Connected: $info"
                    log("connectedNodes: $info")
                }
            } catch (apiEx: ApiException) {
                tvWatchStatus.text = "API Error: status ${apiEx.statusCode}"
                log("ApiException [${apiEx.statusCode}]: ${apiEx.message}")
            } catch (e: Exception) {
                tvWatchStatus.text = "Error: ${e.message}"
                log("Exception: ${e::class.java.name}: ${e.message}")
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
                    log("Cannot send: No connected nodes found.")
                    return@launch
                }

                nodes.forEach { node ->
                    log("Sending $path (${payload.size} bytes) to ${node.displayName}...")
                    val result = withContext(Dispatchers.IO) {
                        Tasks.await(messageClient.sendMessage(node.id, path, payload))
                    }
                    log("Delivered $label! MessageId: $result")
                }
            } catch (apiEx: ApiException) {
                log("Send failed [${apiEx.statusCode}]: ${apiEx.message}")
            } catch (e: Exception) {
                log("Send failed: ${e::class.java.simpleName}: ${e.message}")
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
                    log("Cannot send stop: No connected nodes.")
                    return@launch
                }

                nodes.forEach { node ->
                    log("Sending /wakescience/stop to ${node.displayName}...")
                    val result = withContext(Dispatchers.IO) {
                        Tasks.await(messageClient.sendMessage(node.id, "/wakescience/stop", ByteArray(0)))
                    }
                    log("Delivered stop! MessageId: $result")
                }
            } catch (apiEx: ApiException) {
                log("Stop failed [${apiEx.statusCode}]: ${apiEx.message}")
            } catch (e: Exception) {
                log("Stop failed: ${e::class.java.simpleName}: ${e.message}")
            }
        }
    }
}
