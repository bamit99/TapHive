package com.taphive.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The "who is talking to what" view, backed by retained local history.
 *
 * Polls once per second so asynchronous geo/DNS enrichment appears without a
 * reload, but only rebuilds views when the underlying data actually changes.
 */
class TrafficActivity : Activity() {
    private lateinit var pieApp: PieChartView
    private lateinit var pieCountry: PieChartView
    private lateinit var legendApp: LinearLayout
    private lateinit var legendCountry: LinearLayout
    private lateinit var destinationsList: LinearLayout
    private lateinit var historyInfo: TextView
    private var lastSignature = ""
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traffic)

        pieApp = findViewById(R.id.pie_app)
        pieCountry = findViewById(R.id.pie_country)
        legendApp = findViewById(R.id.legend_app)
        legendCountry = findViewById(R.id.legend_country)
        destinationsList = findViewById(R.id.destinations_list)
        historyInfo = findViewById(R.id.history_info)

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.info).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun render() {
        val app = application as TapHiveApp
        val hours = app.prefs.retentionHours
        historyInfo.text = if (hours <= 0) {
            getString(R.string.history_off)
        } else {
            getString(R.string.history_window, retentionLabel(hours))
        }

        val appShares = if (hours <= 0) emptyList() else app.database.appShares()
        val countryShares = if (hours <= 0) emptyList() else app.database.countryShares()
        val rows = if (hours <= 0) emptyList() else app.database.topDestinations(10)

        val signature = signatureOf(appShares, countryShares, rows)
        if (signature == lastSignature) return
        lastSignature = signature

        // -- By application -------------------------------------------------
        val appTotal = appShares.sumOf { it.second }
        val appSlices = appShares.mapIndexed { index, (uid, bytes) ->
            PieChartView.Slice(
                app.appNameResolver.name(uid),
                bytes.toFloat() / maxOf(appTotal, 1L),
                PIE_COLORS[index % PIE_COLORS.size]
            )
        }
        pieApp.slices = appSlices
        pieApp.centerText = formatBytes(appTotal)
        buildLegend(legendApp, appSlices)

        // -- By country -----------------------------------------------------
        val countryTotal = countryShares.sumOf { it.second }
        val countrySlices = countryShares.mapIndexed { index, (label, bytes) ->
            PieChartView.Slice(
                label,
                bytes.toFloat() / maxOf(countryTotal, 1L),
                PIE_COLORS[index % PIE_COLORS.size]
            )
        }
        pieCountry.slices = countrySlices
        pieCountry.centerText = formatBytes(countryTotal)
        buildLegend(legendCountry, countrySlices)

        // -- Top destinations -----------------------------------------------
        destinationsList.removeAllViews()
        if (rows.isEmpty()) {
            val message = if (hours <= 0) {
                getString(R.string.history_off_hint)
            } else {
                getString(R.string.no_traffic)
            }
            destinationsList.addView(emptyLabel(message))
            return
        }
        for (row in rows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 8, 0, 8)
            }
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val label = row.dnsName ?: row.org ?: row.ip
            val ipText = TextView(this).apply {
                text = label
                setTextColor(context.getColor(R.color.neon_cyan))
                textSize = 14f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            }
            val subText = TextView(this).apply {
                text = buildString {
                    append(row.ip)
                    append(" · port ${row.port} · ${protocolName(row.protocol)}")
                    row.country?.let { append(" · $it") }
                }
                setTextColor(context.getColor(R.color.neon_muted))
                textSize = 10f
                letterSpacing = 0.06f
            }
            left.addView(ipText)
            left.addView(subText)
            val bytesText = TextView(this).apply {
                text = formatBytes(row.bytes)
                setTextColor(context.getColor(R.color.neon_cyan_soft))
                textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            }
            rowView.addView(left)
            rowView.addView(bytesText)
            destinationsList.addView(rowView)
        }
    }

    private fun emptyLabel(message: String) = TextView(this).apply {
        text = message
        setTextColor(context.getColor(R.color.neon_muted))
        textSize = 11f
    }

    private fun buildLegend(container: LinearLayout, slices: List<PieChartView.Slice>) {
        container.removeAllViews()
        if (slices.isEmpty()) {
            container.addView(emptyLabel(getString(R.string.no_traffic)))
            return
        }
        for (slice in slices) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 0, 24, 0)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(8, 8)
                background = ColorDrawable(slice.color)
            }
            val label = TextView(this).apply {
                text = "${slice.label} · ${Math.round(slice.fraction * 100)}%"
                setTextColor(context.getColor(R.color.neon_text))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 6 }
            }
            item.addView(dot)
            item.addView(label)
            container.addView(item)
        }
    }

    private fun signatureOf(
        apps: List<Pair<Int, Long>>,
        countries: List<Pair<String, Long>>,
        rows: List<TrafficDatabase.DestinationRow>
    ): String = buildString {
        append(apps.joinToString { "${it.first}:${it.second}" })
        append('|')
        append(countries.joinToString { "${it.first}:${it.second}" })
        append('|')
        append(rows.joinToString { "${it.ip}:${it.port}:${it.bytes}:${it.country}:${it.org}:${it.dnsName}" })
    }

    private fun retentionLabel(hours: Int): String = when (hours) {
        1 -> "1 HOUR"
        6 -> "6 HOURS"
        24 -> "24 HOURS"
        168 -> "7 DAYS"
        else -> "$hours HOURS"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private fun protocolName(protocol: Int): String = when (protocol) {
        6 -> "TCP"
        17 -> "UDP"
        1 -> "ICMP"
        else -> "IP$protocol"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        // Full ARGB (0xFF alpha) so the slices/legend dots are opaque, not transparent.
        // The 0xFF alpha byte pushes each value above Int.MAX_VALUE, so cast to Int explicitly.
        val PIE_COLORS: List<Int> = listOf(
            0xFF00E5FF.toInt(), // neon cyan
            0xFFFF2D95.toInt(), // neon magenta
            0xFF39FF88.toInt(), // neon green
            0xFFFFB347.toInt(), // amber
            0xFF9D7BFF.toInt(), // violet
            0xFF4D9FFF.toInt(), // blue
            0xFFFF5C5C.toInt(), // red
            0xFF5CFFB7.toInt()  // teal
        )
    }
}
