package com.wakescience.core

import java.nio.ByteBuffer

object BridgeProtocol {
    const val PATH_CONFIG_SYNC = "/wakescience/config_sync"
    const val PATH_START = "/wakescience/start"
    const val PATH_STOP = "/wakescience/stop"
    const val PATH_TELEMETRY = "/wakescience/telemetry"
    const val PATH_STATUS = "/wakescience/status"

    // Config binary: [version:Int][alarmEpochMs:Long][windowMinutes:Int][rampDurationSeconds:Int][baselineRestingBpm:Double][baselineDeepSleepRmssd:Double][motionSensitivity:Float][profileOrdinal:Int][maxHapticIntensity:Int]
    // Total bytes = 4 + 8 + 4 + 4 + 8 + 8 + 4 + 4 + 4 = 44 bytes
    fun serializeConfig(config: WakeEngineConfig): ByteArray {
        val buffer = ByteBuffer.allocate(44)
        buffer.putInt(2) // version 2
        buffer.putLong(config.alarmEpochMs)
        buffer.putInt(config.windowMinutes)
        buffer.putInt(config.rampDurationSeconds)
        buffer.putDouble(config.baselineRestingBpm)
        buffer.putDouble(config.baselineDeepSleepRmssd)
        buffer.putFloat(config.motionSensitivity)
        buffer.putInt(config.vibrationProfile.ordinal)
        buffer.putInt(config.maxHapticIntensity)
        return buffer.array()
    }

    fun parseConfig(data: ByteArray): WakeEngineConfig {
        return try {
            if (data.size < 36) return WakeEngineConfig()
            val buffer = ByteBuffer.wrap(data)
            val version = buffer.int
            val alarmEpochMs = if (version >= 2 && data.size >= 44) buffer.long else 0L
            val windowMinutes = buffer.int
            val rampDurationSeconds = buffer.int
            val baselineRestingBpm = buffer.double
            val baselineDeepSleepRmssd = buffer.double
            val motionSensitivity = buffer.float
            val profileOrdinal = buffer.int
            val maxHapticIntensity = buffer.int

            val profiles = VibrationProfile.values()
            val profile = if (profileOrdinal in profiles.indices) profiles[profileOrdinal] else VibrationProfile.GENTLE_RAMP

            WakeEngineConfig(
                alarmEpochMs = alarmEpochMs,
                windowMinutes = windowMinutes,
                rampDurationSeconds = rampDurationSeconds,
                baselineRestingBpm = baselineRestingBpm,
                baselineDeepSleepRmssd = baselineDeepSleepRmssd,
                motionSensitivity = motionSensitivity,
                vibrationProfile = profile,
                maxHapticIntensity = maxHapticIntensity
            )
        } catch (_: Exception) {
            WakeEngineConfig()
        }
    }

    data class TelemetryData(
        val timestamp: Long,
        val heartRateBpm: Double,
        val rmssd: Double,
        val actigraphyScore: Double,
        val stageOrdinal: Int,
        val isTracking: Boolean
    )

    fun serializeTelemetry(
        heartRateBpm: Double,
        rmssd: Double,
        actigraphyScore: Double,
        stage: SleepStage,
        isTracking: Boolean
    ): ByteArray {
        val buffer = ByteBuffer.allocate(37)
        buffer.putLong(System.currentTimeMillis())
        buffer.putDouble(heartRateBpm)
        buffer.putDouble(rmssd)
        buffer.putDouble(actigraphyScore)
        buffer.putInt(stage.ordinal)
        buffer.put(if (isTracking) 1.toByte() else 0.toByte())
        return buffer.array()
    }

    fun parseTelemetry(data: ByteArray): TelemetryData? {
        return try {
            if (data.size < 37) return null
            val buffer = ByteBuffer.wrap(data)
            TelemetryData(
                timestamp = buffer.long,
                heartRateBpm = buffer.double,
                rmssd = buffer.double,
                actigraphyScore = buffer.double,
                stageOrdinal = buffer.int,
                isTracking = buffer.get() == 1.toByte()
            )
        } catch (_: Exception) {
            null
        }
    }
}
