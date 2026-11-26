package com.taphive.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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
    private lateinit var applicationsList: LinearLayout
    private lateinit var destinationsList: LinearLayout
    private lateinit var tabApps: Button
    private lateinit var tabDestinations: Button
    private lateinit var historyInfo: TextView
    private var showApps = false
    private var lastSignature = ""
    private var querying = false
    private var backfillRequested = false
    private var backCallback: OnBackInvokedCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val queryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
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
        applicationsList = findViewById(R.id.applications_list)
        destinationsList = findViewById(R.id.destinations_list)
        tabApps = findViewById(R.id.tab_apps)
        tabDestinations = findViewById(R.id.tab_destinations)
        historyInfo = findViewById(R.id.history_info)

        tabApps.setOnClickListener { selectTab(true) }
        tabDestinations.setOnClickListener { selectTab(false) }
        findViewById<Button>(R.id.back).setOnClickListener { returnToMain() }
        findViewById<Button>(R.id.info).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { returnToMain() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!
            )
        }
    }

    private fun selectTab(apps: Boolean) {
        if (showApps == apps) return
        showApps = apps
        applicationsList.visibility = if (apps) View.VISIBLE else View.GONE
        destinationsList.visibility = if (apps) View.GONE else View.VISIBLE
        tabApps.setBackgroundResource(
            if (apps) R.drawable.neon_button_cyan else R.drawable.neon_button_ghost
        )
        tabDestinations.setBackgroundResource(
            if (apps) R.drawable.neon_button_ghost else R.drawable.neon_button_cyan
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // API 32 fallback and tolerance for OEM/ADB paths that still call it.
        returnToMain()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backCallback = null
        queryExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Always land on the capture screen, even when launched directly. */
    private fun returnToMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    private fun render() {
        val app = application as TapHiveApp
        val hours = app.prefs.retentionHours
        historyInfo.text = if (hours <= 0) {
            getString(R.string.history_off)
        } else {
            getString(R.string.history_window, retentionLabel(hours))
        }

        if (querying) return
        querying = true
        val enrichment = app.prefs.enrichmentEnabled
        queryExecutor.execute {
            val appRows = if (hours <= 0) emptyList() else app.database.appRows()
            val countryShares = if (hours <= 0 || !enrichment) {
                emptyList()
            } else {
                app.database.countryShares()
            }
            val rows = if (hours <= 0) emptyList() else app.database.topDestinations(10)
            val unresolved = if (enrichment && !backfillRequested) {
                app.database.unresolvedIps(50)
            } else {
                emptyList()
            }
            runOnUiThread {
                querying = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (enrichment && !backfillRequested) {
                    backfillRequested = true
                    unresolved.forEach(app.geoResolver::lookup)
                }
                applyData(app, hours, enrichment, appRows, countryShares, rows)
            }
        }
    }

    private fun applyData(
        app: TapHiveApp,
        hours: Int,
        enrichment: Boolean,
        appRows: List<TrafficDatabase.AppRow>,
        countryShares: List<Pair<String, Long>>,
        rows: List<TrafficDatabase.DestinationRow>
    ) {
        val signature = signatureOf(hours, enrichment, appRows, countryShares, rows)
        if (signature == lastSignature) return
        lastSignature = signature

        // -- By application -------------------------------------------------
        val appTotal = appRows.sumOf { it.bytes }
        val appNames = appRows.map { app.appNameResolver.name(it.uid) }
        val appSlices = appRows.mapIndexed { index, row ->
            PieChartView.Slice(
                appNames[index],
                row.bytes.toFloat() / maxOf(appTotal, 1L),
                PIE_COLORS[index % PIE_COLORS.size]
            )
        }
        pieApp.slices = appSlices
        pieApp.centerText = formatBytes(appTotal)
        buildLegend(legendApp, appSlices)

        // -- By country -----------------------------------------------------
        if (hours <= 0) {
            showCountryState(getString(R.string.country_history_off_hint))
        } else if (!enrichment) {
            showCountryState(getString(R.string.country_enrichment_off_hint))
        } else {
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
        }

        // -- Top lists -------------------------------------------------------
        val emptyMessage = if (hours <= 0) {
            getString(R.string.history_off_hint)
        } else {
            getString(R.string.no_traffic)
        }
        buildAppList(applicationsList, appRows, appNames, appTotal, emptyMessage)
        buildDestinationList(destinationsList, rows, emptyMessage)
    }

    private fun showCountryState(message: String) {
        pieCountry.slices = emptyList()
        pieCountry.centerText = ""
        legendCountry.removeAllViews()
        legendCountry.addView(emptyLabel(message))
    }

    private fun buildAppList(
        container: LinearLayout,
        rows: List<TrafficDatabase.AppRow>,
        names: List<String>,
        total: Long,
        emptyMessage: String
    ) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(emptyLabel(emptyMessage))
            return
        }
        for (index in rows.indices) {
            val row = rows[index]
            val share = Math.round(row.bytes.toFloat() * 100 / maxOf(total, 1L))
            val flowLabel = if (row.flows == 1L) "flow" else "flows"
            addRow(
                container,
                names[index],
                "UID ${row.uid} · $share% of traffic · ${row.flows} $flowLabel",
                formatBytes(row.bytes)
            )
        }
    }

    private fun buildDestinationList(
        container: LinearLayout,
        rows: List<TrafficDatabase.DestinationRow>,
        emptyMessage: String
    ) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(emptyLabel(emptyMessage))
            return
        }
        for (row in rows) {
            val label = row.dnsName ?: row.org ?: row.ip
            val subText = buildString {
                append(row.ip)
                append(" · port ${row.port} · ${protocolName(row.protocol)}")
                row.country?.let { append(" · $it") }
            }
            addRow(container, label, subText, formatBytes(row.bytes))
        }
    }

    private fun addRow(
        container: LinearLayout,
        labelText: String,
        subText: String,
        bytesText: String
    ) {
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
        left.addView(TextView(this).apply {
            text = labelText
            setTextColor(context.getColor(R.color.neon_cyan))
            textSize = 14f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        })
        left.addView(TextView(this).apply {
            text = subText
            setTextColor(context.getColor(R.color.neon_muted))
            textSize = 10f
            letterSpacing = 0.06f
        })
        rowView.addView(left)
        rowView.addView(TextView(this).apply {
            text = bytesText
            setTextColor(context.getColor(R.color.neon_cyan_soft))
            textSize = 12f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        })
        container.addView(rowView)
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
        hours: Int,
        enrichment: Boolean,
        apps: List<TrafficDatabase.AppRow>,
        countries: List<Pair<String, Long>>,
        rows: List<TrafficDatabase.DestinationRow>
    ): String = buildString {
        append(hours).append(':').append(enrichment).append('|')
        append(apps.joinToString { "${it.uid}:${it.bytes}:${it.flows}" })
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
