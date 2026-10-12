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
     * Evaluates a sensor epoch during sleep tracking.
     *
     * RULES:
     * 1. If user is WAKE:
     *    - DO NOT VIBRATE! A user who is already awake does not need an alarm ramp.
     * 2. If current time is BEFORE windowStartMs:
     *    - We are tracking normal nighttime sleep. Never wake early.
     * 3. Inside the smart wake window (windowStartMs <= currentTimestampMs < hardDeadlineMs):
     *    - ONLY trigger if confirmed sleep (DEEP_N3 or consecutive LIGHT_N1_N2) was established,
     *      AND user now exhibits a transient micro-arousal transition into Light Sleep.
     * 4. Hard deadline (currentTimestampMs >= hardDeadlineMs):
     *    - Only trigger if hardDeadlineMs > 0L and currentTimestampMs >= hardDeadlineMs.
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
        // 1. Hard alarm deadline reached
        if (hardDeadlineMs > 0L && currentTimestampMs >= hardDeadlineMs) {
            return EvaluationResult(
                shouldWakeNow = true,
                reason = "Target alarm deadline reached",
                stagedStage = SleepStage.WAKE,
                confidence = 1.0
            )
        }

        // 2. Classify current epoch
        val staging = classifier.classifyEpoch(epoch, baselineRestingBpm, baselineDeepSleepRmssd)

        // 3. User is actively awake
        if (staging.stage == SleepStage.WAKE) {
            return EvaluationResult(
                shouldWakeNow = false,
                reason = if (hasSleepBeenDetected) "Waking naturally - alarm suppressed" else "Awake - monitoring",
                stagedStage = SleepStage.WAKE,
                confidence = staging.confidence
            )
        }

        // 4. If we haven't reached the wake window yet, keep sleeping
        if (windowStartMs > 0L && currentTimestampMs < windowStartMs) {
            return EvaluationResult(
                shouldWakeNow = false,
                reason = "Before smart wake window",
                stagedStage = staging.stage,
                confidence = staging.confidence
            )
        }

        // 5. We are inside the smart window [windowStartMs .. hardDeadlineMs]
        return when (staging.stage) {
            SleepStage.DEEP_N3 -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "Deep Slow-Wave Sleep (N3) - alarm suppressed",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
            SleepStage.REM -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "REM sleep - holding for light stage transition",
                    stagedStage = staging.stage,
                    confidence = staging.confidence
                )
            }
            SleepStage.LIGHT_N1_N2 -> {
                // Must have confirmed actual sleep beforehand
                val isAutonomicArousal = epoch.heartRateBpm >= baselineRestingBpm + 4.0
                val isActigraphyMotion = staging.actigraphyScore >= 0.03

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
            SleepStage.WAKE -> {
                EvaluationResult(
                    shouldWakeNow = false,
                    reason = "Awake",
                    stagedStage = SleepStage.WAKE,
                    confidence = staging.confidence
                )
            }
        }
    }
}
