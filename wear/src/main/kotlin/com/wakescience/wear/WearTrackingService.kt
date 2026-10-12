package com.wakescience.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.wakescience.core.BridgeProtocol
import com.wakescience.core.PhysiologicalEpoch
import com.wakescience.core.SmartWakeEngine
import com.wakescience.core.StimulusRampController
import com.wakescience.core.WakeEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class WearTrackingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "WearTrackingService"
        var currentConfig = WakeEngineConfig()
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var smartWakeEngine = SmartWakeEngine()
    private var rampController = StimulusRampController(currentConfig)
    
    private val bufferLock = Any()
    private val rrBuffer = mutableListOf<Double>()
    private val accelBuffer = mutableListOf<Double>()
    private var latestBpm = 60.0
    private var latestRmssd = 50.0
    private var latestActivity = 0.0
    private var currentStageStr = "IDLE"

    private var windowStartMs = 0L
    private var hardDeadlineMs = 0L
    private var isAlarmTriggered = false
    private var sensorManager: SensorManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received")
        val channelId = "wakescience_tracking"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Sleep Tracking", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WakeScience Active")
            .setContentText("Monitoring physiological arousal threshold")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)

        // Parse intent config or use currentConfig
        intent?.getByteArrayExtra("CONFIG_BYTES")?.let {
            currentConfig = BridgeProtocol.parseConfig(it)
            rampController = StimulusRampController(currentConfig)
        }

        windowStartMs = intent?.getLongExtra("WINDOW_START_MS", 0L) ?: System.currentTimeMillis()
        hardDeadlineMs = intent?.getLongExtra("HARD_DEADLINE_MS", 0L) ?: (System.currentTimeMillis() + (currentConfig.windowMinutes * 60 * 1000L))

        Log.i(TAG, "Tracking window: start=$windowStartMs, deadline=$hardDeadlineMs, profile=${currentConfig.vibrationProfile}")
        broadcastStatusUpdate("Tracking started", latestBpm, latestRmssd, currentStageStr)

        startAccelerometerMonitoring()
        startHealthServicesMonitoring()
        startEpochEvaluationLoop()

        return START_STICKY
    }

    private fun startAccelerometerMonitoring() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accel?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i(TAG, "Registered Accelerometer listener")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register accelerometer: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0] / SensorManager.GRAVITY_EARTH
            val y = event.values[1] / SensorManager.GRAVITY_EARTH
            val z = event.values[2] / SensorManager.GRAVITY_EARTH
            val mag = sqrt((x * x + y * y + z * z).toDouble())
            val deviation = kotlin.math.abs(mag - 1.0) * currentConfig.motionSensitivity

            synchronized(bufferLock) {
                accelBuffer.add(deviation)
                if (accelBuffer.size > 300) accelBuffer.removeAt(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startHealthServicesMonitoring() {
        try {
            val healthClient = HealthServices.getClient(this).measureClient

            val hrCallback = object : MeasureCallback {
                override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                    Log.d(TAG, "HR availability changed: $availability")
                }
                override fun onDataReceived(dataPoints: DataPointContainer) {
                    val samples = dataPoints.getData(DataType.HEART_RATE_BPM)
                    samples.forEach { dp ->
                        latestBpm = dp.value
                        Log.d(TAG, "Heart rate sample: $latestBpm BPM")
                        broadcastStatusUpdate("Tracking (HR: ${latestBpm.toInt()})", latestBpm, latestRmssd, currentStageStr)
                        sendTelemetryToPhone()
                    }
                }
            }

            healthClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
            Log.i(TAG, "Registered MeasureCallback for HEART_RATE_BPM")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register HealthServices: ${e.message}", e)
        }
    }

    private fun startEpochEvaluationLoop() {
        serviceScope.launch {
            while (isActive && !isAlarmTriggered) {
                delay(10_000L) // 10s evaluation cycle

                val epoch = synchronized(bufferLock) {
                    val ep = PhysiologicalEpoch(
                        timestampMs = System.currentTimeMillis(),
                        vectorMagnitudes = accelBuffer.toList(),
                        rrIntervalsMs = rrBuffer.toList(),
                        heartRateBpm = latestBpm
                    )
                    accelBuffer.clear()
                    rrBuffer.clear()
                    ep
                }

                val result = smartWakeEngine.evaluate(
                    epoch = epoch,
                    currentTimestampMs = System.currentTimeMillis(),
                    windowStartMs = windowStartMs,
                    hardDeadlineMs = hardDeadlineMs,
                    baselineRestingBpm = currentConfig.baselineRestingBpm,
                    baselineDeepSleepRmssd = currentConfig.baselineDeepSleepRmssd
                )

                currentStageStr = result.stagedStage.name
                latestActivity = epoch.vectorMagnitudes.average().takeIf { !it.isNaN() } ?: 0.0

                Log.i(TAG, "Epoch evaluated: stage=${result.stagedStage}, shouldWake=${result.shouldWakeNow}, reason=${result.reason}")
                broadcastStatusUpdate(result.reason, latestBpm, latestRmssd, currentStageStr)
                sendTelemetryToPhone()

                if (result.shouldWakeNow) {
                    isAlarmTriggered = true
                    Log.i(TAG, "ALARM TRIGGERED: ${result.reason}")
                    broadcastStatusUpdate("WAKE ALARM TRIGGERED: ${result.reason}", latestBpm, latestRmssd, "WAKE")
                    triggerHapticStimulusRamp()
                    broadcastSmartWakeTriggerToPhone()
                    break
                }
            }
        }
    }

    private fun triggerHapticStimulusRamp() {
        serviceScope.launch {
            val vibrator = getSystemService(Vibrator::class.java)
            var elapsed = 0
            val intensity = currentConfig.maxHapticIntensity.coerceIn(1, 255)
            Log.i(TAG, "Starting stimulus ramp sequence profile=${currentConfig.vibrationProfile.name}")

            while (isActive && elapsed < rampController.rampDurationSeconds) {
                val (pulseMs, intervalMs) = rampController.calculateHapticPattern(elapsed)
                vibrator.vibrate(VibrationEffect.createOneShot(pulseMs, intensity))
                delay(intervalMs)
                elapsed += ((pulseMs + intervalMs) / 1000).toInt().coerceAtLeast(1)
            }
        }
    }

    private fun broadcastStatusUpdate(status: String, bpm: Double, rmssd: Double, stage: String) {
        val intent = Intent("com.wakescience.wear.STATUS_UPDATE").apply {
            putExtra("STATUS", status)
            putExtra("BPM", bpm)
            putExtra("RMSSD", rmssd)
            putExtra("STAGE", stage)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendTelemetryToPhone() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val payload = BridgeProtocol.serializeTelemetry(
                    heartRateBpm = latestBpm,
                    rmssd = latestRmssd,
                    actigraphyScore = latestActivity,
                    currentStage = currentStageStr,
                    reason = "Monitoring",
                    isTracking = true
                )
                val nodeClient = Wearable.getNodeClient(this@WearTrackingService)
                val messageClient = Wearable.getMessageClient(this@WearTrackingService)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, BridgeProtocol.PATH_TELEMETRY, payload)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not send telemetry to phone: ${e.message}")
            }
        }
    }

    private fun broadcastSmartWakeTriggerToPhone() {
        val intent = Intent("com.urbandroid.sleep.alarmclock.START_ALARM").apply {
            putExtra("WAKE_REASON", "LIGHT_SLEEP_AROUSAL_CONFIRMED")
            setPackage("com.urbandroid.sleep")
        }
        sendBroadcast(intent)
        Log.i(TAG, "Dispatched START_ALARM broadcast for Sleep as Android")
    }

    override fun onDestroy() {
        Log.i(TAG, "WearTrackingService destroyed")
        sensorManager?.unregisterListener(this)
        broadcastStatusUpdate("Tracking Stopped", 0.0, 0.0, "STOPPED")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
