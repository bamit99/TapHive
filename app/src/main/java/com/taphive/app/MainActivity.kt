package com.taphive.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var syslogInfo: TextView
    private lateinit var statsInfo: TextView
    private lateinit var captureToggle: Button
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
        statsInfo = findViewById(R.id.stats_info)
        captureToggle = findViewById(R.id.capture_toggle)

        captureToggle.setOnClickListener {
            if (CaptureService.isRunning) {
                CaptureService.stop(this)
            } else {
                requestStart()
            }
            updateStatus()
        }
        findViewById<Button>(R.id.info).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<Button>(R.id.traffic).setOnClickListener {
            startActivity(Intent(this, TrafficActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }

        // Ask once, shortly after launch, so the prompt isn't a cold-start surprise.
        handler.postDelayed({ maybeAskBatteryOptimizations() }, BATTERY_PROMPT_DELAY_MS)

        // After a reboot Android resets the prepared VPN app to "Legacy VPN".
        // prepare() can only run from an Activity, so recover here when capture
        // was left wanted but the background start could not establish.
        handler.postDelayed({ maybePrepareAndResume() }, RESUME_CHECK_DELAY_MS)
    }

    private fun maybePrepareAndResume() {
        val prefs = (application as TapHiveApp).prefs
        if (!prefs.captureWanted || CaptureService.isRunning || CaptureService.starting) return
        val consent = VpnService.prepare(this)
        if (consent == null) {
            startCaptureService()
        } else {
            startActivityForResult(consent, REQ_VPN)
        }
    }

    private fun maybeAskBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = (application as TapHiveApp).prefs
        if (prefs.batteryAsked) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            prefs.markBatteryAsked()
            return
        }
        prefs.markBatteryAsked()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: ActivityNotFoundException) {
            // Some devices don't expose this settings page; nothing else to do.
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
        CaptureService.start(this)
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
        captureToggle.setText(if (running) R.string.stop_capture else R.string.start_capture)
        captureToggle.setBackgroundResource(
            if (running) R.drawable.neon_button_magenta else R.drawable.neon_button_cyan
        )

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
        statsInfo.text = getString(
            R.string.stats_format,
            CaptureService.packetsCaptured,
            app.sender.forwardedEvents
        )
    }

    private companion object {
        const val REQ_VPN = 1001
        const val REQ_NOTIFICATIONS = 1002
        const val POLL_INTERVAL_MS = 1000L
        const val RESUME_CHECK_DELAY_MS = 700L
        const val BATTERY_PROMPT_DELAY_MS = 1500L
    }
}
