package com.wakescience.core

/**
 * Science-backed Smart Wake Decision Engine.
 * Evaluates whether an alarm should be triggered early during the configured window.
 */
class SmartWakeEngine(
    private val classifier: SleepStageClassifier = SleepStageClassifier()
) {

    data class EvaluationResult(
        val shouldWakeNow: Boolean,
        val reason: String,
        val stagedStage: SleepStage,
        val confidence: Double
    )

    /**
     * Evaluates a sensor epoch during the smart alarm window.
     *
     * Rules based on sleep science:
     * 1. Never wake during DEEP_N3 (causes severe sleep inertia).
     * 2. Trigger wake if epoch is LIGHT_N1_N2 AND there is physiological confirmation of arousal (micro-motion or HR spike).
     * 3. Trigger wake immediately if user transitions into WAKE.
     * 4. If current time >= hardDeadlineMs, must trigger regardless of stage.
     */
    fun evaluate(
        epoch: PhysiologicalEpoch,
        currentTimestampMs: Long,
        windowStartMs: Long,
        hardDeadlineMs: Long,
        baselineRestingBpm: Double = 55.0,
        baselineDeepSleepRmssd: Double = 50.0
    ): EvaluationResult {
        // Fallback: Hard deadline hit
        if (currentTimestampMs >= hardDeadlineMs) {
            return EvaluationResult(
                shouldWakeNow = true,
                reason = "Hard deadline reached",
                stagedStage = SleepStage.WAKE,
                confidence = 1.0
            )
        }

        // Outside window: do not wake
        if (currentTimestampMs < windowStartMs) {
            return EvaluationResult(
                shouldWakeNow = false,
                reason = "Before smart wake window",
                stagedStage = SleepStage.DEEP_N3,
                confidence = 1.0
            )
        }

        val staging = classifier.classifyEpoch(epoch, baselineRestingBpm, baselineDeepSleepRmssd)

        return when (staging.stage) {
            SleepStage.DEEP_N3 -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "User is in Slow-Wave Deep Sleep (N3) - waking suppressed to prevent sleep inertia",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
            SleepStage.WAKE -> {
                EvaluationResult(
                    shouldWakeNow = true,
                    reason = "User has naturally transitioned to spontaneous wakefulness",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
            SleepStage.LIGHT_N1_N2 -> {
                // If light sleep is accompanied by autonomic arousal (HR > baseline + 8) or actigraphy burst
                val isAutonomicArousal = epoch.heartRateBpm >= baselineRestingBpm + 8.0
                val isActigraphyMotion = staging.actigraphyScore >= 0.08

                if (isAutonomicArousal || isActigraphyMotion) {
                    EvaluationResult(
                        shouldWakeNow = true,
                        reason = "Optimal Light Sleep arousal detected (HR spike or micro-motion)",
                        stagedStage = staging.stage,
                        confidence = staging.confidence
                    )
                } else {
                    EvaluationResult(
                        shouldWakeNow = false,
                        reason = "In Light Sleep, but waiting for micro-arousal transition",
                        stagedStage = staging.stage,
                        confidence = staging.confidence
                    )
                }
            }
            SleepStage.REM -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "In REM sleep - holding for light stage transition",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
        }
    }
}
