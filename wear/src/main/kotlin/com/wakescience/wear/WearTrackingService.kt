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
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.SampleDataPoint
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
        hardDeadlineMs = intent?.getLongExtra("HARD_DEADLINE_MS", 0L) ?: 0L

        startHealthServicesMonitoring()
        startEpochEvaluationLoop()

        return START_STICKY
    }

    private fun startHealthServicesMonitoring() {
        val healthClient = HealthServices.getClient(this).measureClient

        val hrCallback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
            override fun onDeliverDataPoints(dataPoints: DataPointContainer) {
                val samples = dataPoints.getData(DataType.HEART_RATE_BPM)
                samples.forEach { dp ->
                    latestBpm = dp.value
                }
            }
        }

        healthClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
    }

    private fun startEpochEvaluationLoop() {
        serviceScope.launch {
            while (isActive && !isAlarmTriggered) {
                delay(30_000L) // 30-second epoch

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

                if (result.shouldWakeNow) {
                    isAlarmTriggered = true
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
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
