package com.taphive.app

import java.net.InetAddress
import java.util.Locale

/**
 * Pure IPv4/IPv6 metadata parser. Kept separate from the VPN reader so the
 * boundary logic can be unit-tested without an Android device.
 */
internal object PacketParser {
    fun parse(bytes: ByteArray, length: Int): IpPacket? {
        if (length < 1 || length > bytes.size) return null
        return when (bytes[0].toInt() ushr 4) {
            4 -> parseIpv4(bytes, length)
            6 -> parseIpv6(bytes, length)
            else -> null
        }
    }

    private fun parseIpv4(bytes: ByteArray, length: Int): IpPacket? {
        if (length < 20) return null
        val headerLength = (bytes[0].toInt() and 0x0f) * 4
        if (headerLength < 20 || headerLength > length) return null
        val totalLength = u16(bytes, 2)
        if (totalLength < headerLength || totalLength > length) return null
        // Fragments are not reassembled; a non-first fragment has no ports.
        if (u16(bytes, 6) and 0x3fff != 0) return null
        val protocol = u8(bytes, 9)
        val (sport, dport) = transportPorts(bytes, length, headerLength, protocol)
        return IpPacket(
            protocol,
            endpoint(4, bytes.copyOfRange(12, 16), sport),
            endpoint(4, bytes.copyOfRange(16, 20), dport)
        )
    }

    private fun parseIpv6(bytes: ByteArray, length: Int): IpPacket? {
        if (length < 40) return null
        val payloadLength = u16(bytes, 4)
        if (40 + payloadLength > length) return null
        var offset = 40
        var next = u8(bytes, 6)
        while (next == 0 || next == 43 || next == 44 || next == 60) {
            // IPv6 fragments are not reassembled; do not invent ports for them.
            if (next == 44) return null
            if (length < offset + 8) return null
            val following = u8(bytes, offset)
            val headerLength = (u8(bytes, offset + 1) + 2) * 8
            if (headerLength < 8 || offset + headerLength > length) return null
            offset += headerLength
            next = following
        }
        val (sport, dport) = transportPorts(bytes, length, offset, next)
        return IpPacket(
            next,
            endpoint(6, bytes.copyOfRange(8, 24), sport),
            endpoint(6, bytes.copyOfRange(24, 40), dport)
        )
    }

    private fun transportPorts(
        bytes: ByteArray,
        length: Int,
        offset: Int,
        protocol: Int
    ): Pair<Int, Int> {
        if (protocol != 6 && protocol != 17 && protocol != 132) return 0 to 0
        if (length < offset + 4) return 0 to 0
        return u16(bytes, offset) to u16(bytes, offset + 2)
    }

    private fun endpoint(family: Int, address: ByteArray, port: Int): Endpoint = Endpoint(
        family = family,
        address = String(address, Charsets.ISO_8859_1),
        ip = InetAddress.getByAddress(address).hostAddress ?: "unknown",
        port = port
    )

    fun flowKey(src: Endpoint, dst: Endpoint, protocol: Int): String {
        val endpoints = listOf(src, dst).sortedWith(
            compareBy<Endpoint> { it.family }.thenBy { it.address }.thenBy { it.port }
        )
        val a = endpoints[0]
        val b = endpoints[1]
        return String.format(
            Locale.ROOT,
            "%d|%d|%s:%d|%d|%s:%d",
            protocol, a.family, a.address, a.port, b.family, b.address, b.port
        )
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff

    private fun u16(bytes: ByteArray, index: Int): Int =
        (u8(bytes, index) shl 8) or u8(bytes, index + 1)
}
