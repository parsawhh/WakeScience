package com.wakescience.app

import android.app.Activity
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.wakescience.core.BridgeProtocol
import com.wakescience.core.SleepStage
import com.wakescience.core.VibrationProfile
import com.wakescience.core.WakeEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var tvWatchStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tvLiveBpm: TextView
    private lateinit var tvLiveRmssd: TextView
    private lateinit var tvLiveStage: TextView

    private lateinit var tvValWindow: TextView
    private lateinit var tvValRamp: TextView
    private lateinit var tvValBpm: TextView
    private lateinit var tvValSensitivity: TextView
    private lateinit var tvValIntensity: TextView

    private lateinit var sliderWindow: Slider
    private lateinit var sliderRamp: Slider
    private lateinit var sliderBpm: Slider
    private lateinit var sliderSensitivity: Slider
    private lateinit var sliderIntensity: Slider
    private lateinit var rgVibrationProfile: RadioGroup

    private var currentConfig = WakeEngineConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_WakeScience)
        setContentView(R.layout.activity_main)

        tvWatchStatus = findViewById(R.id.tvWatchStatus)
        tvLogs = findViewById(R.id.tvLogs)
        tvLiveBpm = findViewById(R.id.tvLiveBpm)
        tvLiveRmssd = findViewById(R.id.tvLiveRmssd)
        tvLiveStage = findViewById(R.id.tvLiveStage)

        tvValWindow = findViewById(R.id.tvValWindow)
        tvValRamp = findViewById(R.id.tvValRamp)
        tvValBpm = findViewById(R.id.tvValBpm)
        tvValSensitivity = findViewById(R.id.tvValSensitivity)
        tvValIntensity = findViewById(R.id.tvValIntensity)

        sliderWindow = findViewById(R.id.sliderWindow)
        sliderRamp = findViewById(R.id.sliderRamp)
        sliderBpm = findViewById(R.id.sliderBpm)
        sliderSensitivity = findViewById(R.id.sliderSensitivity)
        sliderIntensity = findViewById(R.id.sliderIntensity)
        rgVibrationProfile = findViewById(R.id.rgVibrationProfile)

        setupSliders()

        findViewById<MaterialButton>(R.id.btnRefreshWatches).setOnClickListener {
            refreshNodes()
        }

        findViewById<MaterialButton>(R.id.btnSyncConfig).setOnClickListener {
            syncConfigToWatch()
        }

        findViewById<MaterialButton>(R.id.btnSendStartWindow).setOnClickListener {
            val payload = BridgeProtocol.serializeConfig(currentConfig)
            sendPayloadToWatch(BridgeProtocol.PATH_START, payload, "Started Smart Window (${currentConfig.windowMinutes}m)")
        }

        findViewById<MaterialButton>(R.id.btnSendTestWake).setOnClickListener {
            val testConfig = currentConfig.copy(windowMinutes = 1, rampDurationSeconds = 10)
            val payload = BridgeProtocol.serializeConfig(testConfig)
            sendPayloadToWatch(BridgeProtocol.PATH_START, payload, "Started Instant Test Wake (10s Ramp)")
        }

        findViewById<MaterialButton>(R.id.btnSendStop).setOnClickListener {
            sendPayloadToWatch(BridgeProtocol.PATH_STOP, ByteArray(0), "Stop Tracking")
        }

        log("WakeScience MD3 Expressive Hub active.")
        refreshNodes()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == BridgeProtocol.PATH_TELEMETRY) {
            val tele = BridgeProtocol.parseTelemetry(event.data) ?: return
            runOnUiThread {
                val stages = SleepStage.values()
                val stage = if (tele.stageOrdinal in stages.indices) stages[tele.stageOrdinal].name else "UNKNOWN"

                tvLiveBpm.text = if (tele.heartRateBpm > 0) "${tele.heartRateBpm.toInt()} BPM" else "-- BPM"
                tvLiveRmssd.text = if (tele.rmssd > 0) "${tele.rmssd.toInt()} ms" else "-- ms"
                tvLiveStage.text = stage
                log("Telemetry: HR=${tele.heartRateBpm.toInt()} Stage=$stage Act=${String.format("%.2f", tele.actigraphyScore)}")
            }
        }
    }

    private fun setupSliders() {
        sliderWindow.addOnChangeListener { _, value, _ ->
            tvValWindow.text = "${value.toInt()} mins"
            updateConfig()
        }

        sliderRamp.addOnChangeListener { _, value, _ ->
            val mins = value.toInt() / 60
            tvValRamp.text = "${value.toInt()} sec (${mins}m)"
            updateConfig()
        }

        sliderBpm.addOnChangeListener { _, value, _ ->
            tvValBpm.text = "${value.toInt()} BPM"
            updateConfig()
        }

        sliderSensitivity.addOnChangeListener { _, value, _ ->
            tvValSensitivity.text = "${String.format("%.1f", value)}x"
            updateConfig()
        }

        sliderIntensity.addOnChangeListener { _, value, _ ->
            val pct = (value / 255.0 * 100).toInt()
            tvValIntensity.text = "$pct%"
            updateConfig()
        }

        rgVibrationProfile.setOnCheckedChangeListener { _, checkedId ->
            val profile = when (checkedId) {
                R.id.rbHeartbeat -> VibrationProfile.HEARTBEAT
                R.id.rbAssertive -> VibrationProfile.ASSERTIVE
                R.id.rbInterval -> VibrationProfile.INTERVAL_BURST
                else -> VibrationProfile.GENTLE_RAMP
            }
            currentConfig = currentConfig.copy(vibrationProfile = profile)
            log("Selected profile: ${profile.displayName}")
        }
    }

    private fun updateConfig() {
        currentConfig = currentConfig.copy(
            windowMinutes = sliderWindow.value.toInt(),
            rampDurationSeconds = sliderRamp.value.toInt(),
            baselineRestingBpm = sliderBpm.value.toDouble(),
            motionSensitivity = sliderSensitivity.value,
            maxHapticIntensity = sliderIntensity.value.toInt()
        )
    }

    private fun syncConfigToWatch() {
        updateConfig()
        val payload = BridgeProtocol.serializeConfig(currentConfig)
        sendPayloadToWatch(BridgeProtocol.PATH_CONFIG_SYNC, payload, "Synced Config (${currentConfig.vibrationProfile.name})")
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLogs.append("[$time] $message\n")
    }

    private fun refreshNodes() {
        scope.launch {
            val nodeClient = Wearable.getNodeClient(this@MainActivity)
            try {
                val nodes: List<Node> = withContext(Dispatchers.IO) {
                    Tasks.await(nodeClient.connectedNodes)
                }
                if (nodes.isEmpty()) {
                    tvWatchStatus.text = "No watches connected via Wearable API"
                    log("Scan: 0 watches connected.")
                } else {
                    val info = nodes.joinToString(", ") { "${it.displayName} (id: ${it.id.take(8)}... nearby=${it.isNearby})" }
                    tvWatchStatus.text = "Connected: $info"
                    log("Connected to: $info")
                }
            } catch (e: Exception) {
                tvWatchStatus.text = "Error: ${e.message}"
                log("Scan error: ${e.message}")
            }
        }
    }

    private fun sendPayloadToWatch(path: String, payload: ByteArray, label: String) {
        scope.launch {
            try {
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
                    log("Dispatching $label to ${node.displayName}...")
                    val result = withContext(Dispatchers.IO) {
                        Tasks.await(messageClient.sendMessage(node.id, path, payload))
                    }
                    log("Ack received! MessageId: $result")
                }
            } catch (e: Exception) {
                log("Send failed: ${e.message}")
            }
        }
    }
}
