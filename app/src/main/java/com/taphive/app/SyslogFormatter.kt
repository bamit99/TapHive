package com.taphive.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure RFC 3164/5424 formatter so header behavior is testable off-device. */
internal object SyslogFormatter {
    fun format(
        event: SyslogEvent,
        facility: Int,
        appName: String,
        host: String,
        pid: Int,
        format: String,
        dropped: Long,
        forwarded: Long,
        now: Date = Date()
    ): String {
        val severity = if (event.event.startsWith("capture_")) 5 else 6
        val pri = facility * 8 + severity
        val safeHost = host.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeApp = appName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val json = if (event.event.startsWith("capture_")) {
            event.copy(
                droppedEvents = dropped,
                eventsForwarded = if (event.event == "capture_stop") forwarded else event.eventsForwarded
            ).toJson()
        } else {
            event.toJson()
        }
        if (format == "rfc5424") {
            // <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG
            val msgId = event.event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(32).ifEmpty { "-" }
            return "<$pri>1 ${UTC_MILLIS.format(event.timestamp)} " +
                "${safeHost.take(255).ifEmpty { "-" }} ${safeApp.take(48).ifEmpty { "-" }} " +
                "$pid $msgId - $json"
        }
        val timestamp = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(now)
        return "<$pri>$timestamp $safeHost $safeApp[$pid]: $json"
    }
}
