package com.taphive.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class AppDetailActivity : Activity() {
    private lateinit var app: TapHiveApp
    private val queryExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)
        app = application as TapHiveApp

        val uid = intent.getIntExtra(EXTRA_UID, -1)
        if (uid < 0) {
            finish()
            return
        }

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.name).text = app.appNameResolver.name(uid)
        findViewById<TextView>(R.id.uid).text = "UID $uid"

        queryExecutor.execute {
            val detail = app.database.appDetail(uid)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(detail)
            }
        }
    }

    private fun render(detail: TrafficDatabase.AppDetail?) {
        if (detail == null) {
            findViewById<TextView>(R.id.total).text = getString(R.string.app_detail_no_data)
            findViewById<TextView>(R.id.sent).text = "—"
            findViewById<TextView>(R.id.received).text = "—"
            findViewById<TextView>(R.id.flows).text = "0"
            return
        }

        findViewById<TextView>(R.id.total).text = formatBytes(detail.bytes)
        val directionKnown = detail.bytesSent + detail.bytesReceived > 0L
        findViewById<TextView>(R.id.sent).text =
            if (directionKnown) formatBytes(detail.bytesSent) else "—"
        findViewById<TextView>(R.id.received).text =
            if (directionKnown) formatBytes(detail.bytesReceived) else "—"
        findViewById<TextView>(R.id.flows).text = detail.flows.toString()
        findViewById<View>(R.id.old_direction).visibility =
            if (directionKnown) View.GONE else View.VISIBLE

        val protocols = findViewById<LinearLayout>(R.id.protocols)
        protocols.removeAllViews()
        for (row in detail.protocols) {
            val sub = if (directionKnown) {
                "${row.flows} flows · sent ${formatBytes(row.bytesSent)} · received ${formatBytes(row.bytesReceived)}"
            } else {
                "${row.flows} flows"
            }
            protocols.addView(
                detailRow(protocolName(row.protocol), sub, formatBytes(row.bytes))
            )
        }

        val destinations = findViewById<LinearLayout>(R.id.destinations)
        destinations.removeAllViews()
        for (row in detail.destinations) {
            val label = row.dnsName ?: row.org ?: row.ip
            val sub = buildString {
                append(row.ip).append(" · port ").append(row.port)
                append(" · ").append(protocolName(row.protocol))
                row.country?.let { append(" · ").append(it) }
            }
            destinations.addView(
                detailRow(label, sub, formatBytes(row.bytes)) {
                    startActivity(
                        Intent(this, IpDetailActivity::class.java).apply {
                            putExtra(IpDetailActivity.EXTRA_IP, row.ip)
                            putExtra(IpDetailActivity.EXTRA_PORT, row.port)
                            putExtra(IpDetailActivity.EXTRA_PROTOCOL, row.protocol)
                            putExtra(IpDetailActivity.EXTRA_UID, row.uid)
                            putExtra(IpDetailActivity.EXTRA_BYTES, row.bytes)
                            putExtra(IpDetailActivity.EXTRA_FLOWS, row.flows)
                            putExtra(IpDetailActivity.EXTRA_DNS, row.dnsName)
                        }
                    )
                }
            )
        }

        val ports = findViewById<LinearLayout>(R.id.ports)
        ports.removeAllViews()
        for (row in detail.ports) {
            ports.addView(
                detailRow(
                    "${row.port} · ${protocolName(row.protocol)}",
                    "${row.flows} flows",
                    formatBytes(row.bytes)
                )
            )
        }
    }

    private fun detailRow(
        title: String,
        subtitle: String,
        value: String,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 8, 0, 8)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.neon_cyan))
            textSize = 14f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        })
        left.addView(TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.neon_muted))
            textSize = 10f
            letterSpacing = 0.06f
        })
        row.addView(left)
        row.addView(TextView(this).apply {
            text = value
            setTextColor(getColor(R.color.neon_cyan_soft))
            textSize = 12f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        })
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
        }
        return row
    }

    private fun protocolName(protocol: Int): String = when (protocol) {
        6 -> "TCP"
        17 -> "UDP"
        1 -> "ICMP"
        else -> "IP$protocol"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 ->
            String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    override fun onDestroy() {
        queryExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_UID = "uid"
    }
}
