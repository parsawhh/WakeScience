package com.wakescience.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.wakescience.core.BridgeProtocol
import com.wakescience.core.PhysiologicalEpoch
import com.wakescience.core.SleepStage
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
        private const val TELEMETRY_THROTTLE_MS = 10_000L // Transmit BT telemetry at most once per 10s
        var currentConfig = WakeEngineConfig()
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var smartWakeEngine = SmartWakeEngine()
    private var rampController = StimulusRampController(currentConfig)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private val bufferLock = Any()
    private val rrBuffer = mutableListOf<Double>()
    private val accelBuffer = mutableListOf<Double>()
    private var latestBpm = 0.0
    private var latestRmssd = 45.0
    private var latestActivity = 0.0
    private var currentStage = SleepStage.WAKE
    private var hasSleepBeenDetected = false
    private var lastTelemetrySentMs = 0L

    private var windowStartMs = 0L
    private var hardDeadlineMs = 0L
    private var isAlarmTriggered = false
    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Vibrator::class.java)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeScience::TrackingLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12-hour timeout
        }
        Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for screen-off execution")
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            startForeground(1001, notification)
        }

        intent?.getByteArrayExtra("CONFIG_BYTES")?.let {
            currentConfig = BridgeProtocol.parseConfig(it)
            rampController = StimulusRampController(currentConfig)
        }

        windowStartMs = intent?.getLongExtra("WINDOW_START_MS", 0L) ?: System.currentTimeMillis()
        hardDeadlineMs = intent?.getLongExtra("HARD_DEADLINE_MS", 0L) ?: (
            if (currentConfig.alarmEpochMs > 0L) currentConfig.alarmEpochMs 
            else System.currentTimeMillis() + (currentConfig.windowMinutes * 60 * 1000L)
        )

        isAlarmTriggered = false
        hasSleepBeenDetected = false

        Log.i(TAG, "Tracking window: start=$windowStartMs, deadline=$hardDeadlineMs, profile=${currentConfig.vibrationProfile}")
        broadcastStatusUpdate("Tracking started", latestBpm, latestRmssd, currentStage.name)

        startHardwareSensors()
        startPassiveHealthMonitoring()
        startEpochEvaluationLoop()

        return START_STICKY
    }

    private fun startHardwareSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

            // Request wake-up version of accelerometer with 500ms max batch latency to allow CPU sleep
            accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 500_000)
                Log.i(TAG, "Registered Accelerometer listener (isWakeUp=${it.isWakeUpSensor})")
            }

            // Request wake-up version of heart rate sensor
            heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            heartRateSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0)
                Log.i(TAG, "Registered Hardware Heart Rate listener (isWakeUp=${it.isWakeUpSensor})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register sensors: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
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
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toDouble()
                if (hr > 30.0 && hr < 220.0) {
                    latestBpm = hr
                    checkAndSendThrottledTelemetry()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startPassiveHealthMonitoring() {
        try {
            val healthClient = HealthServices.getClient(this).passiveMonitoringClient
            val config = PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .build()

            val callback = object : PassiveListenerCallback {
                override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                    val samples = dataPoints.getData(DataType.HEART_RATE_BPM)
                    samples.forEach { dp ->
                        if (dp.value > 30.0) {
                            latestBpm = dp.value
                            checkAndSendThrottledTelemetry()
                        }
                    }
                }
            }

            healthClient.setPassiveListenerCallback(config, callback)
            Log.i(TAG, "Registered HealthServices PassiveMonitoringClient for continuous screen-off telemetry")
        } catch (e: Exception) {
            Log.e(TAG, "HealthServices Passive note: ${e.message}")
        }
    }

    private fun checkAndSendThrottledTelemetry() {
        val now = System.currentTimeMillis()
        if (now - lastTelemetrySentMs >= TELEMETRY_THROTTLE_MS) {
            lastTelemetrySentMs = now
            sendTelemetryToPhone()
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
                    baselineDeepSleepRmssd = currentConfig.baselineDeepSleepRmssd,
                    hasSleepBeenDetected = hasSleepBeenDetected
                )

                currentStage = result.stagedStage
                latestActivity = epoch.vectorMagnitudes.average().takeIf { !it.isNaN() } ?: 0.0

                if (currentStage == SleepStage.DEEP_N3 || currentStage == SleepStage.LIGHT_N1_N2) {
                    hasSleepBeenDetected = true
                }

                Log.i(TAG, "Epoch: stage=${currentStage.name}, shouldWake=${result.shouldWakeNow}, reason=${result.reason}, hasSleep=$hasSleepBeenDetected")
                broadcastStatusUpdate(result.reason, latestBpm, latestRmssd, currentStage.name)
                
                // Always sync telemetry on epoch boundary
                lastTelemetrySentMs = System.currentTimeMillis()
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
            var elapsed = 0
            val intensity = currentConfig.maxHapticIntensity.coerceIn(1, 255)
            Log.i(TAG, "Starting wake stimulus ramp profile=${currentConfig.vibrationProfile.name}")

            while (isActive && isAlarmTriggered && elapsed < rampController.rampDurationSeconds) {
                val (pulseMs, intervalMs) = rampController.calculateHapticPattern(elapsed)
                vibrator?.vibrate(VibrationEffect.createOneShot(pulseMs, intensity))
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
                    stage = currentStage,
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
        Log.i(TAG, "WearTrackingService destroyed - stopping vibration and sensors")
        isAlarmTriggered = false
        vibrator?.cancel()
        sensorManager?.unregisterListener(this)
        broadcastStatusUpdate("Tracking Stopped", 0.0, 0.0, "STOPPED")
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
