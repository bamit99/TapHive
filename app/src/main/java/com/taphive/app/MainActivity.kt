package com.taphive.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var syslogInfo: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        syslogInfo = findViewById(R.id.syslog_info)

        findViewById<Button>(R.id.start).setOnClickListener { requestStart() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            updateStatus()
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusPoll)
    }

    override fun onPause() {
        handler.removeCallbacks(statusPoll)
        super.onPause()
    }

    private fun requestStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN)
        } else {
            startCaptureService()
        }
    }

    private fun startCaptureService() {
        val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) {
            startCaptureService()
        }
    }

    private fun updateStatus() {
        val running = CaptureService.isRunning
        status.setText(if (running) R.string.status_running else R.string.status_idle)
        statusDot.setBackgroundResource(if (running) R.drawable.dot_green else R.drawable.dot_dim)

        if (running && statusDot.animation == null) {
            val pulse = AlphaAnimation(1f, 0.35f).apply {
                duration = 700
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            statusDot.startAnimation(pulse)
        } else {
            statusDot.clearAnimation()
        }

        val app = application as TapHiveApp
        val prefs = app.prefs
        val target = if (prefs.enabled) {
            "SYSLOG ${prefs.transport.uppercase()} → ${prefs.host}:${prefs.port}"
        } else {
            "SYSLOG DISABLED"
        }
        syslogInfo.text = app.sender.lastError?.let { "$target\n$it" } ?: target
    }

    private companion object {
        const val REQ_VPN = 1001
        const val REQ_NOTIFICATIONS = 1002
        const val POLL_INTERVAL_MS = 1000L
    }
}
