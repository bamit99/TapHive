package com.taphive.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IpDetailActivity : Activity() {
    private lateinit var app: TapHiveApp
    private lateinit var ip: String
    private lateinit var refresh: Button
    private val handler = Handler(Looper.getMainLooper())
    private var pollStarted = 0L

    private val poll = object : Runnable {
        override fun run() {
            render()
            val meta = app.database.ipMeta(ip)
            if (meta?.country == null && System.currentTimeMillis() - pollStarted < POLL_MS) {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ip_detail)
        app = application as TapHiveApp

        ip = intent.getStringExtra(EXTRA_IP).orEmpty()
        if (ip.isBlank()) {
            finish()
            return
        }

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        refresh = findViewById(R.id.refresh)
        refresh.setOnClickListener {
            if (app.prefs.enrichmentEnabled) {
                app.geoResolver.lookup(ip)
                startPolling()
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        findViewById<Button>(R.id.data_source).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ipwho.is/")))
        }

        findViewById<TextView>(R.id.address).text = ip
        findViewById<TextView>(R.id.endpoint).text = endpointText()
        findViewById<TextView>(R.id.app).text = appText()
        findViewById<TextView>(R.id.traffic).text = trafficText()
        findViewById<TextView>(R.id.dns).text =
            intent.getStringExtra(EXTRA_DNS).orEmpty().ifBlank { getString(R.string.ip_detail_no_dns) }

        if (app.prefs.enrichmentEnabled) {
            app.geoResolver.lookup(ip)
            startPolling()
        } else {
            render()
        }
    }

    private fun startPolling() {
        pollStarted = System.currentTimeMillis()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun render() {
        val meta = app.database.ipMeta(ip)
        refresh.setText(
            if (app.prefs.enrichmentEnabled) R.string.ip_detail_refresh
            else R.string.ip_detail_enable
        )

        if (meta == null) {
            findViewById<TextView>(R.id.dns).text =
                intent.getStringExtra(EXTRA_DNS).orEmpty().ifBlank {
                    getString(R.string.ip_detail_no_dns)
                }
            findViewById<TextView>(R.id.location).text =
                if (app.prefs.enrichmentEnabled) {
                    getString(R.string.ip_detail_looking_up)
                } else {
                    getString(R.string.ip_detail_lookup_off)
                }
            findViewById<TextView>(R.id.network).text = getString(R.string.ip_detail_no_location)
            findViewById<TextView>(R.id.source).text =
                getString(R.string.ip_detail_attribution)
            return
        }

        findViewById<TextView>(R.id.dns).text =
            meta.dnsName ?: intent.getStringExtra(EXTRA_DNS).orEmpty().ifBlank {
                getString(R.string.ip_detail_no_dns)
            }

        val location = listOfNotNull(meta.country, meta.region, meta.city)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        findViewById<TextView>(R.id.location).text =
            location.ifBlank {
                if (app.prefs.enrichmentEnabled) {
                    getString(R.string.ip_detail_looking_up)
                } else {
                    getString(R.string.ip_detail_lookup_off)
                }
            }

        val network = buildList {
            meta.asn?.let { add("AS$it") }
            meta.org?.takeIf { it.isNotBlank() }?.let(::add)
            meta.isp?.takeIf { it.isNotBlank() && it != meta.org }?.let(::add)
        }.joinToString(" · ")
        findViewById<TextView>(R.id.network).text =
            network.ifBlank { getString(R.string.ip_detail_no_location) }

        val updated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(meta.updatedAt))
        findViewById<TextView>(R.id.source).text =
            "${meta.provider ?: "cached"} · $updated"
    }

    private fun endpointText(): String {
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val protocol = intent.getIntExtra(EXTRA_PROTOCOL, -1)
        return "port $port · ${protocolName(protocol)}"
    }

    private fun appText(): String {
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        return "${app.appNameResolver.name(uid)} · UID $uid"
    }

    private fun trafficText(): String {
        val bytes = intent.getLongExtra(EXTRA_BYTES, 0L)
        val flows = intent.getLongExtra(EXTRA_FLOWS, 0L)
        return "${formatBytes(bytes)} · ${if (flows == 1L) "1 flow" else "$flows flows"}"
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
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_UID = "uid"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_FLOWS = "flows"
        const val EXTRA_DNS = "dns"
        private const val POLL_MS = 15_000L
    }
}
