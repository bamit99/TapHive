package com.taphive.app

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** RFC 3339 UTC timestamp shared by the JSON payload and the RFC 5424 header. */
val UTC_MILLIS: DateTimeFormatter =
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
    val droppedEvents: Long? = null,
    val packetsCaptured: Long? = null,
    val eventsForwarded: Long? = null,
    val dnsQueries: Long? = null,
    val dnsResponses: Long? = null,
    val dnsTimeouts: Long? = null,
    val dnsNxdomain: Long? = null,
    val dnsServfail: Long? = null,
    val dnsOtherErrors: Long? = null,
    val dnsMalformed: Long? = null,
    val appName: String? = null,
    val country: String? = null,
    val region: String? = null,
    val city: String? = null,
    val asn: Int? = null,
    val org: String? = null,
    val isp: String? = null,
    val dnsName: String? = null,
    val enrichmentProvider: String? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("th_schema_version", 2)
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
        putNullable("th_packets_captured", packetsCaptured)
        putNullable("th_events_forwarded", eventsForwarded)
        putNullable("th_dns_queries", dnsQueries)
        putNullable("th_dns_responses", dnsResponses)
        putNullable("th_dns_timeouts", dnsTimeouts)
        putNullable("th_dns_nxdomain", dnsNxdomain)
        putNullable("th_dns_servfail", dnsServfail)
        putNullable("th_dns_other_errors", dnsOtherErrors)
        putNullable("th_dns_malformed", dnsMalformed)
        putNullable("th_app_name", appName)
        putNullable("th_country", country)
        putNullable("th_region", region)
        putNullable("th_city", city)
        putNullable("th_asn", asn)
        putNullable("th_org", org)
        putNullable("th_isp", isp)
        putNullable("th_dns_name", dnsName)
        putNullable("th_enrichment_provider", enrichmentProvider)
    }.toString()
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value != null) put(key, value)
}
