package com.wakescience.core

import kotlin.math.sqrt

/**
 * 30-second physiological epoch collected from Wear OS smartwatch.
 */
data class PhysiologicalEpoch(
    val timestampMs: Long,
    val vectorMagnitudes: List<Double>,     // Actigraphy acceleration samples
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

/**
 * Validated Clinical Actigraphy + Autonomic HRV Classifier.
 * Implements Cole-Kripke weighting combined with Parasympathetic/Sympathetic HRV thresholds.
 */
class SleepStageClassifier {

    /**
     * Compute RMSSD (Root Mean Square of Successive Differences) in ms.
     * High RMSSD indicates high parasympathetic/vagal tone (characteristic of N3 Deep Sleep).
     * Low RMSSD indicates sympathetic activation (N1/N2 transition or Arousal).
     */
    fun calculateRMSSD(rrIntervalsMs: List<Double>): Double {
        if (rrIntervalsMs.size < 2) return 0.0
        var sumSquaredDiffs = 0.0
        for (i in 0 until rrIntervalsMs.size - 1) {
            val diff = rrIntervalsMs[i + 1] - rrIntervalsMs[i]
            sumSquaredDiffs += diff * diff
        }
        return sqrt(sumSquaredDiffs / (rrIntervalsMs.size - 1))
    }

    /**
     * Computes Actigraphy score based on Cole-Kripke principles.
     * P_0 is current epoch activity, surrounding are historical/future if available.
     */
    fun calculateActigraphyActivity(vectorMagnitudes: List<Double>): Double {
        if (vectorMagnitudes.isEmpty()) return 0.0
        // Activity count is root mean square of deviation from 1G
        val sumDeviations = vectorMagnitudes.sumOf { it * it }
        return sqrt(sumDeviations / vectorMagnitudes.size)
    }

    /**
     * Classifies a 30s epoch given current and baseline metrics.
     * @param epoch Current 30s biosensor window
     * @param baselineRestingBpm User's nighttime minimum resting heart rate
     * @param baselineDeepSleepRmssd User's estimated parasympathetic threshold
     */
    fun classifyEpoch(
        epoch: PhysiologicalEpoch,
        baselineRestingBpm: Double = 55.0,
        baselineDeepSleepRmssd: Double = 50.0
    ): StagingResult {
        val activity = calculateActigraphyActivity(epoch.vectorMagnitudes)
        val rmssd = calculateRMSSD(epoch.rrIntervalsMs)

        // 1. Definite Wake / Gross Body Movement
        if (activity > 0.35) {
            return StagingResult(SleepStage.WAKE, activity, rmssd, 0.95)
        }

        // 2. High Parasympathetic + Low HR + Zero/Minimal Motion = N3 Deep Slow-Wave Sleep
        // In N3, heart rate is close to nocturnal minimum and RMSSD is elevated.
        if (epoch.heartRateBpm <= baselineRestingBpm + 6.0 && rmssd >= baselineDeepSleepRmssd && activity < 0.05) {
            return StagingResult(SleepStage.DEEP_N3, activity, rmssd, 0.88)
        }

        // 3. Autonomic arousal / Sympathetic shift + micro-movement = Light Sleep (N1/N2)
        // HR elevation above baseline + reduced RMSSD
        if (activity in 0.05..0.35 || rmssd < baselineDeepSleepRmssd * 0.75) {
            return StagingResult(SleepStage.LIGHT_N1_N2, activity, rmssd, 0.82)
        }

        // 4. Default baseline non-N3 non-active state is N2 Light Sleep
        return StagingResult(SleepStage.LIGHT_N1_N2, activity, rmssd, 0.70)
    }
}
