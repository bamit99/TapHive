package com.taphive.app

import android.content.Context

class Prefs(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("taphive_phase1", Context.MODE_PRIVATE)

    val enabled: Boolean get() = store.getBoolean(KEY_ENABLED, false)
    val host: String get() = store.getString(KEY_HOST, "") ?: ""
    val port: Int get() = store.getInt(KEY_PORT, DEFAULT_PORT)
    val transport: String get() = store.getString(KEY_TRANSPORT, "udp") ?: "udp"
    val facility: Int get() = store.getInt(KEY_FACILITY, DEFAULT_FACILITY)
    val appName: String get() = store.getString(KEY_APP_NAME, "TapHive") ?: "TapHive"

    /** "rfc3164" (default) or "rfc5424" — only the header format changes. */
    val syslogFormat: String
        get() = store.getString(KEY_SYSLOG_FORMAT, DEFAULT_SYSLOG_FORMAT) ?: DEFAULT_SYSLOG_FORMAT

    /** 0 = no local history; otherwise how many hours of flows to keep. */
    val retentionHours: Int get() = store.getInt(KEY_RETENTION_HOURS, DEFAULT_RETENTION_HOURS)

    /** Opt-in for sending public destination IPs to the HTTPS enrichment provider. */
    val enrichmentEnabled: Boolean
        get() = store.getBoolean(KEY_ENRICHMENT_ENABLED, false)

    /** Opt-in for adding app/DNS/geo fields to syslog flow events. */
    val syslogEnriched: Boolean
        get() = store.getBoolean(KEY_SYSLOG_ENRICHED, false)

    /** True while the user wants capture active; survives process death. */
    val captureWanted: Boolean get() = store.getBoolean(KEY_CAPTURE_WANTED, false)

    /** Opt-in for resuming capture after a reboot. Off unless the user enables it. */
    val startAtBoot: Boolean get() = store.getBoolean(KEY_START_AT_BOOT, false)

    /** Set once the battery-optimisation prompt has been shown. */
    val batteryAsked: Boolean get() = store.getBoolean(KEY_BATTERY_ASKED, false)

    fun saveRetention(hours: Int) {
        store.edit().putInt(KEY_RETENTION_HOURS, hours.coerceIn(0, 24 * 30)).apply()
    }

    fun setCaptureWanted(wanted: Boolean) {
        store.edit().putBoolean(KEY_CAPTURE_WANTED, wanted).apply()
    }

    fun setStartAtBoot(enabled: Boolean) {
        store.edit().putBoolean(KEY_START_AT_BOOT, enabled).apply()
    }

    fun setEnrichmentEnabled(enabled: Boolean) {
        store.edit().putBoolean(KEY_ENRICHMENT_ENABLED, enabled).apply()
    }

    fun setSyslogEnriched(enabled: Boolean) {
        store.edit().putBoolean(KEY_SYSLOG_ENRICHED, enabled).apply()
    }

    fun markBatteryAsked() {
        store.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
    }

    fun save(
        enabled: Boolean,
        host: String,
        port: Int,
        transport: String,
        facility: Int,
        appName: String,
        format: String
    ) {
        store.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .putString(KEY_TRANSPORT, transport)
            .putInt(KEY_FACILITY, facility)
            .putString(KEY_APP_NAME, appName.trim().ifEmpty { "TapHive" })
            .putString(KEY_SYSLOG_FORMAT, if (format == "rfc5424") "rfc5424" else "rfc3164")
            .apply()
    }

    fun validate(host: String, port: Int, facility: Int): String? = when {
        host.isBlank() -> "Host is required"
        port !in 1..65535 -> "Port must be 1-65535"
        facility !in 0..23 -> "Facility must be 0-23"
        else -> null
    }

    private companion object {
        const val KEY_ENABLED = "syslog_enabled"
        const val KEY_HOST = "syslog_host"
        const val KEY_PORT = "syslog_port"
        const val KEY_TRANSPORT = "syslog_transport"
        const val KEY_FACILITY = "syslog_facility"
        const val KEY_APP_NAME = "syslog_app_name"
        const val KEY_SYSLOG_FORMAT = "syslog_format"
        const val DEFAULT_SYSLOG_FORMAT = "rfc3164"
        const val KEY_RETENTION_HOURS = "retention_hours"
        const val KEY_ENRICHMENT_ENABLED = "external_enrichment_enabled"
        const val KEY_SYSLOG_ENRICHED = "syslog_enriched"
        const val KEY_CAPTURE_WANTED = "capture_wanted"
        const val KEY_START_AT_BOOT = "start_at_boot"
        const val KEY_BATTERY_ASKED = "battery_asked"
        const val DEFAULT_PORT = 514
        const val DEFAULT_FACILITY = 13
        const val DEFAULT_RETENTION_HOURS = 24
    }
}
