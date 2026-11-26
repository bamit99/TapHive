package com.taphive.app

import java.net.InetAddress
import java.util.LinkedHashMap

/**
 * Extracts A/AAAA answers from DNS response payloads seen on port 53.
 *
 * This observes names the device actually resolved. Traffic that bypasses
 * port 53 (DoH/DoT) is not visible here; ip-api's org/isp/as fields are the
 * fallback label for those endpoints.
 */
class DnsNameParser {
    data class Query(val id: Int, val name: String, val type: Int)

    /** Extract the first question without storing or emitting the name. */
    fun parseQuery(payload: ByteArray, length: Int): Query? {
        if (length < 12) return null
        val flags = u16(payload, 2)
        if (flags and 0x8000 != 0) return null
        if (u16(payload, 4) <= 0) return null
        val parsed = readName(payload, 12, length) ?: return null
        if (parsed.second + 4 > length) return null
        return Query(
            id = u16(payload, 0),
            name = parsed.first,
            type = u16(payload, parsed.second)
        )
    }

    /** DNS RCODE from a response, or null if this is not a response. */
    fun responseCode(payload: ByteArray, length: Int): Int? {
        if (length < 12) return null
        val flags = u16(payload, 2)
        if (flags and 0x8000 == 0) return null
        return flags and 0x000f
    }

    /** ip -> hostname, first answer wins. */
    fun parse(payload: ByteArray, length: Int): Map<String, String> {
        if (length < 12) return emptyMap()
        val flags = u16(payload, 2)
        if (flags and 0x8000 == 0) return emptyMap() // query, not a response
        if (flags and 0x000F != 0) return emptyMap() // non-NOERROR rcode
        val questionCount = u16(payload, 4)
        val answerCount = u16(payload, 6)
        if (answerCount <= 0 || questionCount > 32 || answerCount > 128) return emptyMap()

        var cursor = 12
        val questions = ArrayList<String>(questionCount)
        repeat(questionCount) {
            val parsed = readName(payload, cursor, length) ?: return emptyMap()
            questions.add(parsed.first)
            cursor = parsed.second + 4 // QTYPE + QCLASS
            if (cursor > length) return emptyMap()
        }

        val found = LinkedHashMap<String, String>()
        repeat(answerCount) {
            if (cursor + 10 > length) return@repeat
            val parsed = readName(payload, cursor, length) ?: return@repeat
            // readName may end exactly at the payload boundary. Validate the
            // fixed answer header against that returned offset, not `cursor`.
            if (parsed.second + 10 > length) return@repeat
            val type = u16(payload, parsed.second)
            val rdLength = u16(payload, parsed.second + 8)
            val rdata = parsed.second + 10
            if (rdata + rdLength > length) return@repeat
            val label = parsed.first.ifBlank { questions.firstOrNull().orEmpty() }
            if (label.isNotBlank()) {
                if (type == 1 && rdLength == 4) {
                    val ip = ByteArray(4) { i -> payload[rdata + i] }
                        .joinToString(".") { (it.toInt() and 0xff).toString() }
                    found.putIfAbsent(ip, label)
                } else if (type == 28 && rdLength == 16) {
                    val ip = InetAddress.getByAddress(payload.copyOfRange(rdata, rdata + 16)).hostAddress
                    if (ip != null) found.putIfAbsent(ip, label)
                }
            }
            cursor = rdata + rdLength
        }
        return found
    }

    /** Reads a possibly-compressed DNS name; returns the name and the next offset. */
    private fun readName(bytes: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var cursor = offset
        var end = offset
        var jumped = false
        var hops = 0
        while (true) {
            if (cursor >= length || hops++ > 64) return null
            val len = u8(bytes, cursor)
            if (len == 0) {
                if (!jumped) end = cursor + 1
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (cursor + 1 >= length) return null
                val pointer = ((len and 0x3F) shl 8) or u8(bytes, cursor + 1)
                if (pointer < 12 || pointer >= length) return null
                if (!jumped) end = cursor + 2
                cursor = pointer
                jumped = true
                continue
            }
            if (len > 63 || cursor + 1 + len > length) return null
            labels.add(String(bytes, cursor + 1, len, Charsets.US_ASCII))
            cursor += 1 + len
            if (!jumped) end = cursor
        }
        return labels.joinToString(".").take(200) to end
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff

    private fun u16(bytes: ByteArray, index: Int): Int = (u8(bytes, index) shl 8) or u8(bytes, index + 1)
}
