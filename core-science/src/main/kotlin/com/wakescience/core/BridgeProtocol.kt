package com.wakescience.core

import org.json.JSONObject

/**
 * Robust JSON-based communication protocol across the Wearable Data Layer.
 * Replaces raw ByteBuffers with self-describing, versioned payloads.
 */
object BridgeProtocol {
    const val PATH_CONFIG_SYNC = "/wakescience/config_sync"
    const val PATH_START = "/wakescience/start"
    const val PATH_STOP = "/wakescience/stop"
    const val PATH_TELEMETRY = "/wakescience/telemetry"
    const val PATH_STATUS = "/wakescience/status"

    fun serializeConfig(config: WakeEngineConfig): ByteArray {
        val json = JSONObject().apply {
            put("windowMinutes", config.windowMinutes)
            put("rampDurationSeconds", config.rampDurationSeconds)
            put("baselineRestingBpm", config.baselineRestingBpm)
            put("baselineDeepSleepRmssd", config.baselineDeepSleepRmssd)
            put("motionSensitivity", config.motionSensitivity.toDouble())
            put("vibrationProfile", config.vibrationProfile.name)
            put("maxHapticIntensity", config.maxHapticIntensity)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseConfig(data: ByteArray): WakeEngineConfig {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val profileStr = json.optString("vibrationProfile", VibrationProfile.GENTLE_RAMP.name)
            val profile = try {
                VibrationProfile.valueOf(profileStr)
            } catch (_: Exception) {
                VibrationProfile.GENTLE_RAMP
            }

            WakeEngineConfig(
                windowMinutes = json.optInt("windowMinutes", 30),
                rampDurationSeconds = json.optInt("rampDurationSeconds", 300),
                baselineRestingBpm = json.optDouble("baselineRestingBpm", 55.0),
                baselineDeepSleepRmssd = json.optDouble("baselineDeepSleepRmssd", 50.0),
                motionSensitivity = json.optDouble("motionSensitivity", 1.0).toFloat(),
                vibrationProfile = profile,
                maxHapticIntensity = json.optInt("maxHapticIntensity", 255)
            )
        } catch (_: Exception) {
            WakeEngineConfig()
        }
    }

    fun serializeTelemetry(
        heartRateBpm: Double,
        rmssd: Double,
        actigraphyScore: Double,
        currentStage: String,
        reason: String,
        isTracking: Boolean
    ): ByteArray {
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("heartRateBpm", heartRateBpm)
            put("rmssd", rmssd)
            put("actigraphyScore", actigraphyScore)
            put("currentStage", currentStage)
            put("reason", reason)
            put("isTracking", isTracking)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseTelemetry(data: ByteArray): JSONObject? {
        return try {
            JSONObject(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
