package com.taphive.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

class SyslogFormatterTest {
    private val flow = SyslogEvent(
        sessionId = "session-1",
        event = "flow_open",
        timestamp = Instant.parse("2026-11-26T11:15:13.845Z"),
        flowId = "1",
        uid = 10194,
        ipVersion = 4,
        protocol = 6,
        srcIp = "10.0.0.2",
        srcPort = 52864,
        dstIp = "1.1.1.1",
        dstPort = 443
    )

    @Test
    fun formatsRfc5424FlowHeader() {
        val line = SyslogFormatter.format(
            flow, facility = 13, appName = "TapHive", host = "SM-P615",
            pid = 123, format = "rfc5424", dropped = 0, forwarded = 0,
            now = Date(0)
        )
        assertTrue(line.startsWith("<110>1 2026-11-26T11:15:13.845Z SM-P615 TapHive 123 flow_open - {"))
        assertTrue(line.contains("\"th_flow_id\":\"1\""))
    }

    @Test
    fun formatsRfc3164LifecycleHeader() {
        val event = SyslogEvent(
            sessionId = "session-1",
            event = "capture_start",
            timestamp = Instant.parse("2026-11-26T11:15:13.845Z")
        )
        val line = SyslogFormatter.format(
            event, facility = 13, appName = "TapHive", host = "SM-P615",
            pid = 123, format = "rfc3164", dropped = 7, forwarded = 9,
            now = Date(0)
        )
        assertTrue(line.startsWith("<109>"))
        assertTrue(line.contains(" SM-P615 TapHive[123]: {"))
        assertTrue(line.contains("\"th_dropped_events\":7"))
    }

    @Test
    fun sanitisesHostAndAppName() {
        val line = SyslogFormatter.format(
            flow, facility = 13, appName = "Tap/Hive", host = "SM P615",
            pid = 1, format = "rfc5424", dropped = 0, forwarded = 0,
            now = Date(0)
        )
        assertTrue(line.contains(" SM_P615 Tap_Hive 1 flow_open - "))
    }
}
