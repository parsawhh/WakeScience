package com.wakescience.app

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Official Sleep as Android Wearable API Provider Bridge.
 * Spec: https://sleep.urbandroid.org/docs/devs/wearable_api.html
 *
 * Dispatches standard explicit broadcasts to com.urbandroid.sleep
 * with movement batch data, real-time heart rate, and R-R intervals.
 */
object SleepAsAndroidProvider {

    private const val TAG = "SleepAsAndroidProvider"
    private const val SLEEP_PACKAGE = "com.urbandroid.sleep"

    // Actions sent to Sleep as Android
    const val ACTION_CONFIRM_CONNECTED = "com.urbandroid.sleep.watch.CONFIRM_CONNECTED"
    const val ACTION_DATA_UPDATE = "com.urbandroid.sleep.watch.DATA_UPDATE"
    const val ACTION_HR_DATA_UPDATE = "com.urbandroid.sleep.watch.HR_DATA_UPDATE"
    const val ACTION_EXTRA_DATA_UPDATE = "com.urbandroid.sleep.ACTION_EXTRA_DATA_UPDATE"
    const val ACTION_START_ALARM = "com.urbandroid.sleep.alarmclock.START_ALARM"

    // Actions received from Sleep as Android
    const val ACTION_CHECK_CONNECTED = "com.urbandroid.sleep.watch.CHECK_CONNECTED"
    const val ACTION_START_TRACKING = "com.urbandroid.sleep.watch.START_TRACKING"
    const val ACTION_STOP_TRACKING = "com.urbandroid.sleep.watch.STOP_TRACKING"
    const val ACTION_SET_PAUSE = "com.urbandroid.sleep.watch.SET_PAUSE"
    const val ACTION_SET_SUSPENDED = "com.urbandroid.sleep.watch.SET_SUSPENDED"
    const val ACTION_SET_BATCH_SIZE = "com.urbandroid.sleep.watch.SET_BATCH_SIZE"

    // Extras
    const val EXTRA_MAX_RAW_DATA = "MAX_RAW_DATA"
    const val EXTRA_DATA = "DATA"
    const val EXTRA_DATA_HR = "com.urbandroid.sleep.EXTRA_DATA_HR"
    const val EXTRA_DATA_RR = "com.urbandroid.sleep.EXTRA_DATA_RR"
    const val EXTRA_DATA_TIMESTAMP = "com.urbandroid.sleep.EXTRA_DATA_TIMESTAMP"

    /**
     * Responds to Sleep as Android connection ping.
     */
    fun confirmConnected(context: Context) {
        val intent = Intent(ACTION_CONFIRM_CONNECTED).apply {
            setPackage(SLEEP_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "Sent CONFIRM_CONNECTED to Sleep as Android")
    }

    /**
     * Sends 10-second aggregated movement data array (actigraphy) to Sleep as Android.
     */
    fun sendMovementData(context: Context, maxRawData: FloatArray) {
        if (maxRawData.isEmpty()) return
        val intent = Intent(ACTION_DATA_UPDATE).apply {
            putExtra(EXTRA_MAX_RAW_DATA, maxRawData)
            setPackage(SLEEP_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Sent movement DATA_UPDATE (${maxRawData.size} values) to Sleep as Android")
    }

    /**
     * Sends heart rate (BPM) updates to Sleep as Android.
     */
    fun sendHeartRate(context: Context, bpm: Float) {
        if (bpm <= 30.0f) return
        val intent = Intent(ACTION_HR_DATA_UPDATE).apply {
            putExtra(EXTRA_DATA, floatArrayOf(bpm))
            setPackage(SLEEP_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Sent HR_DATA_UPDATE ($bpm BPM) to Sleep as Android")
    }

    /**
     * Triggers the smart alarm in Sleep as Android immediately upon light sleep micro-arousal.
     */
    fun triggerSmartAlarm(context: Context, reason: String = "LIGHT_SLEEP_AROUSAL_CONFIRMED") {
        val intent = Intent(ACTION_START_ALARM).apply {
            putExtra("WAKE_REASON", reason)
            setPackage(SLEEP_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "Sent START_ALARM ($reason) to Sleep as Android")
    }
}
