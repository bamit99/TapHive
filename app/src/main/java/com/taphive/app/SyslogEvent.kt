package com.taphive.app

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val UTC_MILLIS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

data class SyslogEvent(
    val sessionId: String,
    val event: String,
    val timestamp: Instant = Instant.now(),
    val flowId: String? = null,
    val uid: Int? = null,
    val ipVersion: Int? = null,
    val protocol: Int? = null,
    val srcIp: String? = null,
    val srcPort: Int? = null,
    val dstIp: String? = null,
    val dstPort: Int? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val status: String? = null,
    val droppedEvents: Long? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("th_schema_version", 1)
        put("th_session_id", sessionId)
        put("th_event", event)
        put("th_timestamp", UTC_MILLIS.format(timestamp))
        putNullable("th_flow_id", flowId)
        putNullable("th_uid", uid)
        putNullable("th_ip_version", ipVersion)
        putNullable("th_protocol", protocol)
        putNullable("th_src_ip", srcIp)
        putNullable("th_src_port", srcPort)
        putNullable("th_dst_ip", dstIp)
        putNullable("th_dst_port", dstPort)
        put("th_bytes_sent", bytesSent)
        put("th_bytes_received", bytesReceived)
        put("th_packets_sent", packetsSent)
        put("th_packets_received", packetsReceived)
        putNullable("th_status", status)
        putNullable("th_dropped_events", droppedEvents)
    }.toString()
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value != null) put(key, value)
}
