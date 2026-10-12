package com.wakescience.wear

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        private const val REQ_FOREGROUND_PERMS = 101
        private const val REQ_BACKGROUND_PERMS = 102
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvBpm: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("STATUS") ?: "Tracking Active"
            val bpm = intent?.getDoubleExtra("BPM", 0.0) ?: 0.0
            tvStatus.text = "Status: $status"
            if (bpm > 0) {
                tvBpm.text = "Heart Rate: ${bpm.toInt()} BPM"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvBpm = findViewById(R.id.tvBpm)

        findViewById<Button>(R.id.btnGrantPerms).setOnClickListener {
            requestPermissionsStepByStep()
        }

        findViewById<Button>(R.id.btnStartTest).setOnClickListener {
            val now = System.currentTimeMillis()
            val intent = Intent(this, WearTrackingService::class.java).apply {
                putExtra("WINDOW_START_MS", now)
                putExtra("HARD_DEADLINE_MS", now + (30 * 60 * 1000))
            }
            startForegroundService(intent)
            tvStatus.text = "Status: Service Started"
        }

        findViewById<Button>(R.id.btnTestWakeRamp).setOnClickListener {
            val now = System.currentTimeMillis()
            val intent = Intent(this, WearTrackingService::class.java).apply {
                putExtra("WINDOW_START_MS", now - 1000)
                putExtra("HARD_DEADLINE_MS", now + 3000) // 3 seconds deadline -> instant wake
            }
            startForegroundService(intent)
            tvStatus.text = "Status: Wake Ramp Triggered!"
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            val intent = Intent(this, WearTrackingService::class.java)
            stopService(intent)
            tvStatus.text = "Status: Stopped"
            tvBpm.text = "Heart Rate: -- BPM"
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.wakescience.wear.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun requestPermissionsStepByStep() {
        val foregroundPerms = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.VIBRATE,
            Manifest.permission.WAKE_LOCK
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            foregroundPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = foregroundPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), REQ_FOREGROUND_PERMS)
        } else {
            checkAndRequestBackgroundSensors()
        }
    }

    private fun checkAndRequestBackgroundSensors() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS_BACKGROUND)
                != PackageManager.PERMISSION_GRANTED) {
                tvStatus.text = "Please allow 'All the time' sensor permission"
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BODY_SENSORS_BACKGROUND),
                    REQ_BACKGROUND_PERMS
                )
                return
            }
        }
        tvStatus.text = "All Permissions Granted"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND_PERMS -> {
                checkAndRequestBackgroundSensors()
            }
            REQ_BACKGROUND_PERMS -> {
                tvStatus.text = "Background Sensors Configured"
            }
        }
    }
}
