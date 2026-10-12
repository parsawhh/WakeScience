package com.wakescience.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.wakescience.core.PhysiologicalEpoch
import com.wakescience.core.SmartWakeEngine
import com.wakescience.core.StimulusRampController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Health Foreground Service running directly on Wear OS (Samsung Galaxy Watch).
 * Declared with foregroundServiceType="health" to prevent Samsung Knox / Doze kills.
 */
class WearTrackingService : Service() {

    companion object {
        private const val TAG = "WearTrackingService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val smartWakeEngine = SmartWakeEngine()
    private val rampController = StimulusRampController(rampDurationSeconds = 300)
    
    private val bufferLock = Any()
    private val rrBuffer = mutableListOf<Double>()
    private val accelBuffer = mutableListOf<Double>()
    private var latestBpm = 60.0

    private var windowStartMs = 0L
    private var hardDeadlineMs = 0L
    private var isAlarmTriggered = false

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

        windowStartMs = intent?.getLongExtra("WINDOW_START_MS", 0L) ?: 0L
        hardDeadlineMs = intent?.getLongExtra("HARD_DEADLINE_MS", 0L) ?: (System.currentTimeMillis() + 1800000L)

        Log.i(TAG, "Tracking window: start=$windowStartMs, deadline=$hardDeadlineMs")
        broadcastStatusUpdate("Tracking started", latestBpm)

        startHealthServicesMonitoring()
        startEpochEvaluationLoop()

        return START_STICKY
    }

    private fun broadcastStatusUpdate(status: String, bpm: Double) {
        val intent = Intent("com.wakescience.wear.STATUS_UPDATE").apply {
            putExtra("STATUS", status)
            putExtra("BPM", bpm)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

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
                        broadcastStatusUpdate("Tracking (HR: ${latestBpm.toInt()})", latestBpm)
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
                delay(10_000L) // 10s check for responsiveness in test/production

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
                    hardDeadlineMs = hardDeadlineMs
                )

                Log.i(TAG, "Epoch evaluated: stage=${result.stagedStage}, shouldWake=${result.shouldWakeNow}, reason=${result.reason}")

                if (result.shouldWakeNow) {
                    isAlarmTriggered = true
                    Log.i(TAG, "ALARM TRIGGERED: ${result.reason}")
                    broadcastStatusUpdate("WAKE ALARM TRIGGERED: ${result.reason}", latestBpm)
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
            Log.i(TAG, "Starting stimulus ramp sequence")
            while (isActive && elapsed < 300) {
                val (pulseMs, intervalMs) = rampController.calculateHapticPattern(elapsed)
                vibrator.vibrate(VibrationEffect.createOneShot(pulseMs, VibrationEffect.DEFAULT_AMPLITUDE))
                delay(intervalMs)
                elapsed += ((pulseMs + intervalMs) / 1000).toInt()
            }
        }
    }

    private fun broadcastSmartWakeTriggerToPhone() {
        // Broadcasts standard Sleep as Android intent and WakeScience payload
        val intent = Intent("com.urbandroid.sleep.alarmclock.START_ALARM").apply {
            putExtra("WAKE_REASON", "LIGHT_SLEEP_AROUSAL_CONFIRMED")
            setPackage("com.urbandroid.sleep")
        }
        sendBroadcast(intent)
        Log.i(TAG, "Dispatched START_ALARM broadcast for Sleep as Android")
    }

    override fun onDestroy() {
        Log.i(TAG, "WearTrackingService destroyed")
        broadcastStatusUpdate("Tracking Stopped", 0.0)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
