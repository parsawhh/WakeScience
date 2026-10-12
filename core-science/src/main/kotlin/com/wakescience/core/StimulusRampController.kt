package com.wakescience.core

import kotlin.math.min

enum class VibrationProfile(val displayName: String, val description: String) {
    GENTLE_RAMP("Gentle Ramp (Clinical)", "Logarithmic micro-taps expanding gently from 40ms to 250ms"),
    HEARTBEAT("Biorhythmic Heartbeat", "Double-thump diastolic/systolic pulse syncing with arousal"),
    ASSERTIVE("Assertive Escalation", "Crisp motor pulses with rapidly accelerating duty-cycle"),
    INTERVAL_BURST("Interval Wave", "Alternating wave packets with rest periods")
}

data class WakeEngineConfig(
    val alarmEpochMs: Long = 0L,         // Specific target wake time
    val windowMinutes: Int = 30,
    val rampDurationSeconds: Int = 300,
    val baselineRestingBpm: Double = 55.0,
    val baselineDeepSleepRmssd: Double = 50.0,
    val motionSensitivity: Float = 1.0f,
    val vibrationProfile: VibrationProfile = VibrationProfile.GENTLE_RAMP,
    val maxHapticIntensity: Int = 255
)

class StimulusRampController(
    val config: WakeEngineConfig = WakeEngineConfig()
) {
    val rampDurationSeconds: Int = config.rampDurationSeconds

    fun calculateVolumeFraction(elapsedSeconds: Int): Float {
        if (elapsedSeconds <= 0) return 0.05f
        if (elapsedSeconds >= rampDurationSeconds) return 1.0f

        val progress = elapsedSeconds.toFloat() / rampDurationSeconds.toFloat()
        val curve = (Math.exp(progress.toDouble()) - 1.0) / (Math.E - 1.0)
        return curve.toFloat().coerceIn(0.05f, 1.0f)
    }

    fun calculateHapticPattern(elapsedSeconds: Int): Pair<Long, Long> {
        val fraction = min(1.0f, elapsedSeconds.toFloat() / rampDurationSeconds.coerceAtLeast(10).toFloat())

        return when (config.vibrationProfile) {
            VibrationProfile.GENTLE_RAMP -> {
                val pulseMs = (40 + (220 * fraction)).toLong()
                val intervalMs = (3200 - (2400 * fraction)).toLong().coerceAtLeast(600)
                Pair(pulseMs, intervalMs)
            }
            VibrationProfile.HEARTBEAT -> {
                val pulseMs = (70 + (90 * fraction)).toLong()
                val intervalMs = (1600 - (900 * fraction)).toLong().coerceAtLeast(500)
                Pair(pulseMs, intervalMs)
            }
            VibrationProfile.ASSERTIVE -> {
                val pulseMs = (120 + (280 * fraction)).toLong()
                val intervalMs = (2000 - (1600 * fraction)).toLong().coerceAtLeast(300)
                Pair(pulseMs, intervalMs)
            }
            VibrationProfile.INTERVAL_BURST -> {
                val pulseMs = (200 + (150 * fraction)).toLong()
                val intervalMs = (2500 - (1500 * fraction)).toLong().coerceAtLeast(800)
                Pair(pulseMs, intervalMs)
            }
        }
    }
}
