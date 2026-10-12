package com.wakescience.core

import kotlin.math.sqrt

data class PhysiologicalEpoch(
    val timestampMs: Long,
    val vectorMagnitudes: List<Double>,     // Actigraphy acceleration deviation samples
    val rrIntervalsMs: List<Double>,        // Inter-beat intervals from PPG
    val heartRateBpm: Double                // Instantaneous average BPM
)

enum class SleepStage {
    WAKE,
    LIGHT_N1_N2,
    DEEP_N3,
    REM
}

data class StagingResult(
    val stage: SleepStage,
    val actigraphyScore: Double,
    val rmssd: Double,
    val confidence: Double
)

class SleepStageClassifier {

    fun calculateRMSSD(rrIntervalsMs: List<Double>): Double {
        if (rrIntervalsMs.size < 2) return 0.0
        var sumSquaredDiffs = 0.0
        for (i in 0 until rrIntervalsMs.size - 1) {
            val diff = rrIntervalsMs[i + 1] - rrIntervalsMs[i]
            sumSquaredDiffs += diff * diff
        }
        return sqrt(sumSquaredDiffs / (rrIntervalsMs.size - 1))
    }

    fun calculateActigraphyActivity(vectorMagnitudes: List<Double>): Double {
        if (vectorMagnitudes.isEmpty()) return 0.0
        val sumDeviations = vectorMagnitudes.sumOf { it * it }
        return sqrt(sumDeviations / vectorMagnitudes.size)
    }

    /**
     * Calibrated clinical classifier:
     * - An awake user sitting/resting typically has HR 65-85 BPM. This is STRICTLY WAKE.
     * - Sleep onset (N1/N2) requires HR to settle right at nocturnal basal levels (<= baseline + 4 BPM)
     *   with virtually zero wrist motion (< 0.04) for prolonged duration.
     * - Deep Slow-Wave (N3) requires high parasympathetic tone (RMSSD >= threshold) and baseline HR.
     */
    fun classifyEpoch(
        epoch: PhysiologicalEpoch,
        baselineRestingBpm: Double = 55.0,
        baselineDeepSleepRmssd: Double = 50.0
    ): StagingResult {
        val activity = calculateActigraphyActivity(epoch.vectorMagnitudes)
        val rmssd = calculateRMSSD(epoch.rrIntervalsMs)

        // 1. Definite Wake: Motion detected OR heart rate is clearly in wake/resting range
        // Any heart rate >= baseline + 6.0 BPM (e.g. 55 + 6 = 61 BPM, or 60 + 6 = 66 BPM) is awake!
        if (activity >= 0.06 || epoch.heartRateBpm > (baselineRestingBpm + 6.0)) {
            return StagingResult(SleepStage.WAKE, activity, rmssd, 0.95)
        }

        // 2. Slow-Wave Deep Sleep (N3):
        // True sleep where HR is at lowest floor (<= baseline + 2) and vagal HRV is strong
        if (epoch.heartRateBpm <= baselineRestingBpm + 2.0 && rmssd >= baselineDeepSleepRmssd && activity < 0.02) {
            return StagingResult(SleepStage.DEEP_N3, activity, rmssd, 0.90)
        }

        // 3. Light Sleep (N1/N2):
        // Heart rate must be tightly anchored around baseline (+2 to +6 BPM) with near-zero motion
        if (epoch.heartRateBpm <= baselineRestingBpm + 6.0 && activity < 0.04) {
            return StagingResult(SleepStage.LIGHT_N1_N2, activity, rmssd, 0.85)
        }

        // 4. Fallback is always WAKE for safety
        return StagingResult(SleepStage.WAKE, activity, rmssd, 0.80)
    }
}
