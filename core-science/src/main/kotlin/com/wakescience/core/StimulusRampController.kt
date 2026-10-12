package com.wakescience.core

import kotlin.math.min

/**
 * Vibration Profiles for custom sensory awakening.
 */
enum class VibrationProfile(val displayName: String, val description: String) {
    GENTLE_RAMP("Gentle Ramp (Clinical)", "Logarithmic micro-taps expanding gently from 40ms to 250ms"),
    HEARTBEAT("Biorhythmic Heartbeat", "Double-thump diastolic/systolic pulse syncing with arousal"),
    ASSERTIVE("Assertive Escalation", "Crisp motor pulses with rapidly accelerating duty-cycle"),
    INTERVAL_BURST("Interval Wave", "Alternating wave packets with rest periods")
}

/**
 * Configurable engine parameters synchronized between Phone and Watch.
 */
data class WakeEngineConfig(
    val windowMinutes: Int = 30,
    val rampDurationSeconds: Int = 300,
    val baselineRestingBpm: Double = 55.0,
    val baselineDeepSleepRmssd: Double = 50.0,
    val motionSensitivity: Float = 1.0f, // 0.5 (low) to 2.0 (high)
    val vibrationProfile: VibrationProfile = VibrationProfile.GENTLE_RAMP,
    val maxHapticIntensity: Int = 255 // 0-255
)

/**
 * Acoustic & Haptic Ramp Controller.
 * Implements McFarlane et al. (PLoS ONE 2020) and Williams (1964) Auditory Arousal Threshold dynamics:
 * - Exponential volume curve: sub-threshold initial acoustic stimulation (25 dB to 75 dB).
 * - Multi-profile haptic pulse modulation.
 */
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

    /**
     * Calculates pulse duration and silent interval based on the chosen profile and elapsed time.
     */
    fun calculateHapticPattern(elapsedSeconds: Int): Pair<Long, Long> {
        val fraction = min(1.0f, elapsedSeconds.toFloat() / rampDurationSeconds.coerceAtLeast(10).toFloat())

        return when (config.vibrationProfile) {
            VibrationProfile.GENTLE_RAMP -> {
                val pulseMs = (40 + (220 * fraction)).toLong()
                val intervalMs = (3200 - (2400 * fraction)).toLong().coerceAtLeast(600)
                Pair(pulseMs, intervalMs)
            }
            VibrationProfile.HEARTBEAT -> {
                // Simulates lub-dub rhythm that speeds up
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
