package com.wakescience.core

/**
 * Science-backed Smart Wake Decision Engine.
 * Evaluates whether an alarm should be triggered during the configured smart wake window.
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
     * CRITICAL BUG FIX FOR PREMATURE VIBRATIONS:
     * - In previous code, if stage == WAKE or (stage == LIGHT && motion), it instantly triggered the alarm!
     *   That caused the watch to vibrate immediately while the user was simply awake testing it!
     * - Fix:
     *   1. If user is completely awake (SleepStage.WAKE) BEFORE the deadline, DO NOT trigger the wake alarm ramp!
     *      They are already awake. It only wakes if transition from sleep to arousal occurs inside the window,
     *      or when the hard deadline is actually reached.
     *   2. Never trigger outside the active window.
     */
    fun evaluate(
        epoch: PhysiologicalEpoch,
        currentTimestampMs: Long,
        windowStartMs: Long,
        hardDeadlineMs: Long,
        baselineRestingBpm: Double = 55.0,
        baselineDeepSleepRmssd: Double = 50.0,
        hasSleepBeenDetected: Boolean = false
    ): EvaluationResult {
        // 1. Fallback: Hard deadline hit -> must wake user
        if (currentTimestampMs >= hardDeadlineMs && hardDeadlineMs > 0L) {
            return EvaluationResult(
                shouldWakeNow = true,
                reason = "Hard alarm deadline reached",
                stagedStage = SleepStage.WAKE,
                confidence = 1.0
            )
        }

        // 2. Before smart wake window
        if (currentTimestampMs < windowStartMs) {
            return EvaluationResult(
                shouldWakeNow = false,
                reason = "Before smart wake window",
                stagedStage = SleepStage.WAKE,
                confidence = 1.0
            )
        }

        val staging = classifier.classifyEpoch(epoch, baselineRestingBpm, baselineDeepSleepRmssd)

        return when (staging.stage) {
            SleepStage.DEEP_N3 -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "Deep Slow-Wave Sleep (N3) - alarm suppressed",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
            SleepStage.LIGHT_N1_N2 -> {
                // If user was asleep and we detect a micro-arousal transition in light sleep inside the window
                val isAutonomicArousal = epoch.heartRateBpm >= baselineRestingBpm + 8.0
                val isActigraphyMotion = staging.actigraphyScore >= 0.08

                if (hasSleepBeenDetected && (isAutonomicArousal || isActigraphyMotion)) {
                    EvaluationResult(
                        shouldWakeNow = true,
                        reason = "Micro-arousal detected during Light Sleep window",
                        stagedStage = staging.stage,
                        confidence = staging.confidence
                    )
                } else {
                    EvaluationResult(
                        shouldWakeNow = false,
                        reason = "Light Sleep - waiting for optimal arousal epoch",
                        stagedStage = staging.stage,
                        confidence = staging.confidence
                    )
                }
            }
            SleepStage.REM -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "REM sleep - holding for light stage transition",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
            SleepStage.WAKE -> {
                // User is awake! If they were sleeping, they woke naturally. If they were never asleep, don't buzz!
                if (hasSleepBeenDetected) {
                    EvaluationResult(
                        shouldWakeNow = true,
                        reason = "Spontaneous morning wakefulness detected",
                        stagedStage = staging.stage,
                        confidence = staging.confidence
                    )
                } else {
                    EvaluationResult(
                        shouldWakeNow = false,
                        reason = "User currently awake - monitoring",
                        stagedStage = staging.stage,
                        confidence = staging.confidence
                    )
                }
            }
        }
    }
}
