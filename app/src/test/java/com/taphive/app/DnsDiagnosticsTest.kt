package com.taphive.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsDiagnosticsTest {
    private val diagnostics = DnsDiagnostics(DnsNameParser())

    @Test
    fun countsQueryAndNxdomainResponse() {
        val query = query(id = 0x1234)
        diagnostics.observe(false, "1.1.1.1", 53, query, query.size)
        val response = response(id = 0x1234, rcode = 3)
        diagnostics.observe(true, "1.1.1.1", 53, response, response.size)

        val snapshot = diagnostics.snapshot()
        assertEquals(1L, snapshot.queries)
        assertEquals(1L, snapshot.responses)
        assertEquals(1L, snapshot.nxdomain)
        assertEquals(0L, snapshot.timeouts)
    }

    @Test
    fun expiresUnansweredQuery() {
        val query = query(id = 7)
        diagnostics.observe(false, "1.1.1.1", 53, query, query.size)
        diagnostics.expire(System.currentTimeMillis() + 6_000L)
        assertEquals(1L, diagnostics.snapshot().timeouts)
    }

    @Test
    fun ignoresUnrelatedUdpPort() {
        val query = query(id = 9)
        diagnostics.observe(false, "1.1.1.1", 5353, query, query.size)
        val snapshot = diagnostics.snapshot()
        assertEquals(0L, snapshot.queries)
        assertEquals(0L, snapshot.responses)
        assertEquals(0L, snapshot.timeouts)
        assertEquals(0L, snapshot.malformed)
    }

    @Test
    fun countsMalformedPacket() {
        diagnostics.observe(false, "1.1.1.1", 53, byteArrayOf(1, 2, 3), 3)
        assertEquals(1L, diagnostics.snapshot().malformed)
    }

    private fun query(id: Int): ByteArray {
        val out = ByteArray(12 + 1 + 1 + 4)
        u16(out, 0, id)
        u16(out, 2, 0x0100)
        u16(out, 4, 1)
        out[12] = 0
        u16(out, 13, 1)
        u16(out, 15, 1)
        return out
    }

    private fun response(id: Int, rcode: Int): ByteArray {
        val out = ByteArray(12)
        u16(out, 0, id)
        u16(out, 2, 0x8180 or rcode)
        u16(out, 4, 1)
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
