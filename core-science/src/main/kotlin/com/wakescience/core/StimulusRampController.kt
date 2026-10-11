package com.wakescience.core

import kotlin.math.ln
import kotlin.math.min

/**
 * Acoustic & Haptic Ramp Controller.
 * Implements McFarlane et al. (PLoS ONE 2020) and Williams (1964) Auditory Arousal Threshold dynamics:
 * - Exponential volume curve: sub-threshold initial acoustic stimulation (25 dB to 75 dB).
 * - Rhythmic melodic stimulus.
 * - Haptic pulse modulation (expanding duty cycle on watch actuator).
 */
class StimulusRampController(
    val rampDurationSeconds: Int = 300 // 5-minute ramp default
) {

    /**
     * Calculates the volume factor [0.0 .. 1.0] at a given second into the ramp.
     * Uses logarithmic/perceptual curve matching human auditory response (Weber-Fechner Law).
     */
    fun calculateVolumeFraction(elapsedSeconds: Int): Float {
        if (elapsedSeconds <= 0) return 0.05f
        if (elapsedSeconds >= rampDurationSeconds) return 1.0f

        val progress = elapsedSeconds.toFloat() / rampDurationSeconds.toFloat()
        // Logarithmic perception mapping: (e^(progress) - 1) / (e - 1)
        val curve = (Math.exp(progress.toDouble()) - 1.0) / (Math.E - 1.0)
        return curve.toFloat().coerceIn(0.05f, 1.0f)
    }

    /**
     * Calculates the haptic vibration pulse parameters (durationMs, intervalMs).
     * Begins with tiny subtle wrist taps and gradually increases density.
     */
    fun calculateHapticPattern(elapsedSeconds: Int): Pair<Long, Long> {
        val fraction = min(1.0f, elapsedSeconds.toFloat() / rampDurationSeconds.toFloat())
        // Vibrate duration grows from 40ms (bare tap) to 250ms (assertive vibration)
        val pulseMs = (40 + (210 * fraction)).toLong()
        // Interval shrinks from 3000ms (every 3s) to 800ms
        val intervalMs = (3000 - (2200 * fraction)).toLong()
        return Pair(pulseMs, intervalMs)
    }
}
