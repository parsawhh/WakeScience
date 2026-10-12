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
     * - Fix: Awake user sitting or lying down is NOT N1/N2 unless sleep is established.
     * - Baseline resting BPM is nighttime sleeping HR. Daytime awake HR is significantly higher.
     */
    fun classifyEpoch(
        epoch: PhysiologicalEpoch,
        baselineRestingBpm: Double = 55.0,
        baselineDeepSleepRmssd: Double = 50.0
    ): StagingResult {
        val activity = calculateActigraphyActivity(epoch.vectorMagnitudes)
        val rmssd = calculateRMSSD(epoch.rrIntervalsMs)

        // 1. Definite Wake: Motion detected OR heart rate significantly above sleeping baseline
        // During awake state, sympathetic tone keeps HR at least 10-15 BPM above nocturnal sleeping minimum
        if (activity > 0.15 || epoch.heartRateBpm >= baselineRestingBpm + 14.0) {
            return StagingResult(SleepStage.WAKE, activity, rmssd, 0.95)
        }

        // 2. High Parasympathetic + Low HR + Zero Motion = N3 Deep Slow-Wave Sleep
        if (epoch.heartRateBpm <= baselineRestingBpm + 5.0 && rmssd >= baselineDeepSleepRmssd && activity < 0.03) {
            return StagingResult(SleepStage.DEEP_N3, activity, rmssd, 0.90)
        }

        // 3. Light Sleep (N1/N2): Only when HR is settled near resting baseline with mild movement
        if (epoch.heartRateBpm <= baselineRestingBpm + 10.0 && activity < 0.12) {
            return StagingResult(SleepStage.LIGHT_N1_N2, activity, rmssd, 0.80)
        }

        // 4. Default state for active/conscious user
        return StagingResult(SleepStage.WAKE, activity, rmssd, 0.70)
    }
}
