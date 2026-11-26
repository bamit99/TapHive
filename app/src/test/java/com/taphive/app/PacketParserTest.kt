package com.taphive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class PacketParserTest {
    @Test
    fun parsesIpv4TcpPorts() {
        val packet = ipv4(protocol = 6, srcPort = 12345, dstPort = 443)
        val parsed = PacketParser.parse(packet, packet.size)!!
        assertEquals(6, parsed.protocol)
        assertEquals("10.0.0.2", parsed.src.ip)
        assertEquals("1.1.1.1", parsed.dst.ip)
        assertEquals(12345, parsed.src.port)
        assertEquals(443, parsed.dst.port)
    }

    @Test
    fun rejectsIpv4Fragments() {
        val packet = ipv4(protocol = 17, srcPort = 5353, dstPort = 53)
        packet[6] = 0x20 // MF
        assertNull(PacketParser.parse(packet, packet.size))
    }

    @Test
    fun rejectsTruncatedIpv4() {
        val packet = ipv4(protocol = 6, srcPort = 1, dstPort = 2)
        packet[2] = 0
        packet[3] = 60 // total length says 60, only 40 supplied
        assertNull(PacketParser.parse(packet, packet.size))
    }

    @Test
    fun parsesIpv6TcpPorts() {
        val packet = ipv6(nextHeader = 6, srcPort = 5555, dstPort = 443)
        val parsed = PacketParser.parse(packet, packet.size)!!
        assertEquals(6, parsed.protocol)
        assertEquals(6, parsed.src.family)
        assertEquals(5555, parsed.src.port)
        assertEquals(443, parsed.dst.port)
    }

    @Test
    fun rejectsIpv6Fragments() {
        val packet = ipv6(nextHeader = 44, srcPort = 1, dstPort = 2)
        assertNull(PacketParser.parse(packet, packet.size))
    }

    @Test
    fun flowKeyIsDirectionIndependent() {
        val a = endpoint("10.0.0.2", 1111)
        val b = endpoint("1.1.1.1", 443)
        assertEquals(
            PacketParser.flowKey(a, b, 6),
            PacketParser.flowKey(b, a, 6)
        )
    }

    private fun endpoint(ip: String, port: Int) = Endpoint(
        family = 4,
        address = String(InetAddress.getByName(ip).address, Charsets.ISO_8859_1),
        ip = ip,
        port = port
    )

    private fun ipv4(protocol: Int, srcPort: Int, dstPort: Int): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[2] = 0
        packet[3] = 40
        packet[8] = 64
        packet[9] = protocol.toByte()
        InetAddress.getByName("10.0.0.2").address.copyInto(packet, 12)
        InetAddress.getByName("1.1.1.1").address.copyInto(packet, 16)
        u16(packet, 20, srcPort)
        u16(packet, 22, dstPort)
        return packet
    }

    private fun ipv6(nextHeader: Int, srcPort: Int, dstPort: Int): ByteArray {
        val packet = ByteArray(60)
        packet[0] = 0x60
        packet[4] = 0
        packet[5] = 20
        packet[6] = nextHeader.toByte()
        packet[7] = 64
        InetAddress.getByName("2001:db8::1").address.copyInto(packet, 8)
        InetAddress.getByName("2001:db8::2").address.copyInto(packet, 24)
        u16(packet, 40, srcPort)
        u16(packet, 42, dstPort)
        return packet
    }

    private fun u16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
