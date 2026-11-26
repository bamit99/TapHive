package com.taphive.app

import android.net.Network
import android.net.VpnService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

interface TunnelForwarder {
    fun forward(packet: IpPacket, raw: ByteArray, length: Int)
    fun stop()
}

/**
 * Minimal transparent IPv4 NAT for TCP/UDP traffic entering the VPN tun.
 *
 * Local connections are mapped to protected sockets on the underlying network;
 * replies are rewritten back to the tun address so the device keeps working.
 * Only metadata is ever handed to the event sink.
 */
class Ipv4TunnelForwarder(
    private val vpn: VpnService,
    private val network: Network?,
    private val tun: TunWriter,
    private val onInbound: (IpPacket, Int) -> Unit,
    private val localIp: String = "10.0.0.2",
    private val onUdpPayload: (Int, ByteArray, Int) -> Unit = { _, _, _ -> }
) : TunnelForwarder {

    private data class SessionKey(
        val protocol: Int,
        val localIp: String,
        val localPort: Int,
        val remoteIp: String,
        val remotePort: Int
    )

    private abstract inner class Session(
        val key: SessionKey,
        val packet: IpPacket
    ) {
        @Volatile
        var lastSeen: Long = System.currentTimeMillis()

        @Volatile
        var closed = false

        abstract fun closeSession()
    }

    private inner class UdpSession(
        key: SessionKey,
        packet: IpPacket,
        private val socket: DatagramSocket,
        private val remote: InetSocketAddress
    ) : Session(key, packet) {
        init {
            Thread({ receiveLoop() }, "taphive-udp-${key.localPort}-${key.remotePort}").also {
                it.isDaemon = true
                it.start()
            }
        }

        fun send(payload: ByteArray) {
            if (closed) return
            socket.send(DatagramPacket(payload, payload.size, remote))
        }

        private fun receiveLoop() {
            val buffer = ByteArray(MAX_UDP_PAYLOAD)
            while (!closed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    onUdpPayload(key.remotePort, payload, payload.size)
                    val reply = IpPacket(
                        protocol = 17,
                        src = endpointOf(this@UdpSession.packet.dst.ip, this@UdpSession.packet.dst.port),
                        dst = endpointOf(this@UdpSession.packet.src.ip, this@UdpSession.packet.src.port)
                    )
                    tun.write(
                        buildUdp(
                            srcIp = reply.src.ip,
                            dstIp = reply.dst.ip,
                            srcPort = reply.src.port,
                            dstPort = reply.dst.port,
                            payload = payload
                        )
                    )
                    onInbound(this@UdpSession.packet, payload.size)
                    lastSeen = System.currentTimeMillis()
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastSeen > UDP_TIMEOUT_MS) {
                        closeSession()
                        break
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    break
                }
            }
        }

        override fun closeSession() {
            if (closed) return
            closed = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
            sessions.remove(key, this)
        }
    }

    private enum class TcpState { SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT }

    private inner class TcpSession(
        key: SessionKey,
        packet: IpPacket,
        val clientIsn: Long
    ) : Session(key, packet) {
        val serverIsn: Long = u32(randomBytes(), 0)
        private val outgoing = LinkedBlockingQueue<ByteArray>()
        private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var connector: Thread? = null
        private var reader: Thread? = null

        @Volatile
        private var state = TcpState.SYN_RECEIVED

        @Volatile
        var clientExpectedSeq = 1L

        @Volatile
        var clientAck = 0L

        @Volatile
        var clientWindow = 65535L

        // SYN consumes one sequence number, so the first data byte lives at serverIsn + 1.
        @Volatile
        var serverBytesSent = 1L

        init {
            connector = Thread({ connectAndWrite() }, "taphive-tcp-out-${key.localPort}-${key.remotePort}").also {
                it.isDaemon = true
                it.start()
            }
        }

        fun sendSynAck() {
            val options = byteArrayOf(2, 4, 0x05, 0x50) // MSS 1360
            sendSegment(
                seq = serverIsn,
                ack = wrap(clientIsn + 1),
                flags = FLAG_SYN or FLAG_ACK,
                window = 65535,
                options = options
            )
        }

        fun sendAck() {
            sendSegment(
                seq = wrap(serverIsn + serverBytesSent),
                ack = wrap(clientIsn + clientExpectedSeq),
                flags = FLAG_ACK
            )
        }

        fun sendFin() {
            sendSegment(
                seq = wrap(serverIsn + serverBytesSent),
                ack = wrap(clientIsn + clientExpectedSeq),
                flags = FLAG_FIN or FLAG_ACK
            )
        }

        fun sendRst() {
            sendSegment(
                seq = wrap(serverIsn + serverBytesSent),
                ack = 0L,
                flags = FLAG_RST or FLAG_ACK
            )
        }

        fun acceptPayload(payload: ByteArray, relativeSeq: Long) {
            when {
                relativeSeq > clientExpectedSeq -> {
                    // Gap in the client stream; ignore until retransmitted.
                }
                relativeSeq < clientExpectedSeq -> sendAck()
                else -> {
                    outgoing.offer(payload.copyOf())
                    clientExpectedSeq += payload.size
                    sendAck()
                }
            }
            if (state == TcpState.SYN_RECEIVED && clientAck == 1L) {
                state = TcpState.ESTABLISHED
            }
        }

        fun requestClose() {
            state = TcpState.CLOSE_WAIT
            outgoing.offer(EMPTY_BYTES)
            sendAck()
        }

        private fun sendSegment(
            seq: Long,
            ack: Long,
            flags: Int,
            window: Int = 65535,
            options: ByteArray = EMPTY_BYTES,
            payload: ByteArray = EMPTY_BYTES
        ) {
            if (closed || tunClosed.get()) return
            tun.write(
                buildTcp(
                    srcIp = packet.dst.ip,
                    dstIp = packet.src.ip,
                    srcPort = packet.dst.port,
                    dstPort = packet.src.port,
                    seq = seq,
                    ack = ack,
                    flags = flags,
                    window = window,
                    options = options,
                    payload = payload
                )
            )
        }

        private fun connectAndWrite() {
            try {
                val s = Socket()
                protectSocket(s)
                s.connect(InetSocketAddress(key.remoteIp, key.remotePort), CONNECT_TIMEOUT_MS)
                s.soTimeout = SOCKET_TIMEOUT_MS
                s.tcpNoDelay = true
                if (closed) {
                    s.close()
                    return
                }
                socket = s
                output = s.getOutputStream()
                input = s.getInputStream()
                state = TcpState.ESTABLISHED
                reader = Thread({ readFromNetwork() }, "taphive-tcp-in-${key.localPort}-${key.remotePort}").also {
                    it.isDaemon = true
                    it.start()
                }
                while (!closed) {
                    val chunk = outgoing.poll(500, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        checkSynTimeout()
                        continue
                    }
                    if (chunk.isEmpty()) {
                        s.shutdownOutput()
                        break
                    }
                    output?.write(chunk)
                    output?.flush()
                    lastSeen = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                if (!closed) sendRst()
                closeSession()
            }
        }

        private fun readFromNetwork() {
            val stream = input ?: return
            val buffer = ByteArray(MAX_TCP_PAYLOAD)
            while (!closed) {
                if (serverBytesSent - clientAck >= clientWindow) {
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val count = try {
                    stream.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    break
                }
                if (count < 0) {
                    sendFin()
                    break
                }
                val payload = buffer.copyOf(count)
                val start = serverBytesSent
                serverBytesSent += count
                sendSegment(
                    seq = wrap(serverIsn + start),
                    ack = wrap(clientIsn + clientExpectedSeq),
                    flags = FLAG_PSH or FLAG_ACK,
                    payload = payload
                )
                onInbound(packet, count)
                lastSeen = System.currentTimeMillis()
            }
            closeSession()
        }

        private fun checkSynTimeout() {
            if (state == TcpState.SYN_RECEIVED &&
                System.currentTimeMillis() - lastSeen > SYN_TIMEOUT_MS
            ) {
                closeSession()
            }
        }

        override fun closeSession() {
            if (closed) return
            closed = true
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            connector?.interrupt()
            reader?.interrupt()
            outgoing.clear()
            sessions.remove(key, this)
        }
    }

    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val closed = AtomicBoolean(false)
    private val tunClosed = AtomicBoolean(false)
    private val ipId = AtomicLong(1)

    override fun forward(packet: IpPacket, raw: ByteArray, length: Int) {
        if (closed.get() || packet.protocol != 6 && packet.protocol != 17) return
        if (packet.src.ip != localIp) return

        val totalLength = min(length, u16(raw, 2))
        val headerLength = (u8(raw, 0) and 0x0f) * 4
        if (headerLength < 20 || totalLength <= headerLength) return

        val key = SessionKey(
            protocol = packet.protocol,
            localIp = packet.src.ip,
            localPort = packet.src.port,
            remoteIp = packet.dst.ip,
            remotePort = packet.dst.port
        )
        var session = sessions[key]
        if (session == null) {
            synchronized(sessions) {
                session = sessions[key] ?: createSession(key, packet, raw, headerLength, totalLength)
                    .also { created -> if (created != null) sessions[key] = created }
            }
        }
        val active = session ?: return
        if (active.closed) {
            sessions.remove(key, active)
            return
        }
        active.lastSeen = System.currentTimeMillis()

        when (active) {
            is UdpSession -> {
                // Skip the IP header and the 8-byte UDP header.
                val payload = raw.copyOfRange(headerLength + 8, totalLength)
                if (payload.isNotEmpty()) active.send(payload)
            }
            is TcpSession -> handleTcp(active, raw, headerLength, totalLength)
        }
    }

    private fun createSession(
        key: SessionKey,
        packet: IpPacket,
        raw: ByteArray,
        ipHeaderLength: Int,
        totalLength: Int
    ): Session? = when (key.protocol) {
        17 -> {
            val socket = DatagramSocket()
            protectSocket(socket)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            UdpSession(key, packet, socket, InetSocketAddress(key.remoteIp, key.remotePort))
        }
        6 -> {
            val tcpOffset = ipHeaderLength
            if (tcpOffset + 20 > totalLength) null
            else {
                TcpSession(key, packet, u32(raw, tcpOffset + 4))
            }
        }
        else -> null
    }

    private fun handleTcp(session: TcpSession, raw: ByteArray, ipHeaderLength: Int, totalLength: Int) {
        val tcpOffset = ipHeaderLength
        val dataOffset = (u8(raw, tcpOffset + 12) ushr 4) * 4
        if (dataOffset < 20 || tcpOffset + dataOffset > totalLength) return
        val flags = u8(raw, tcpOffset + 13)
        val seq = u32(raw, tcpOffset + 4)
        val ack = u32(raw, tcpOffset + 8)

        if (flags and FLAG_RST != 0) {
            session.closeSession()
            return
        }
        if (flags and FLAG_ACK != 0) {
            session.clientAck = relative(ack, session.serverIsn)
            // We do not negotiate window scaling in our SYN-ACK, so honor the raw window.
            val advertised = u16(raw, tcpOffset + 14).toLong()
            session.clientWindow = min(MAX_WINDOW, advertised)
        }
        if (flags and FLAG_SYN != 0 && flags and FLAG_ACK == 0) {
            session.sendSynAck()
            return
        }

        val payloadLength = totalLength - (tcpOffset + dataOffset)
        if (payloadLength > 0) {
            val payload = raw.copyOfRange(tcpOffset + dataOffset, totalLength)
            session.acceptPayload(payload, relative(seq, session.clientIsn))
        }
        if (flags and FLAG_FIN != 0) session.requestClose()
    }

    override fun stop() {
        if (!closed.compareAndSet(false, true)) return
        tunClosed.set(true)
        sessions.values.toList().forEach { it.closeSession() }
        sessions.clear()
    }

    private fun protectSocket(socket: DatagramSocket) {
        vpn.protect(socket)
        network?.bindSocket(socket)
    }

    private fun protectSocket(socket: Socket) {
        vpn.protect(socket)
        network?.bindSocket(socket)
    }

    private fun buildUdp(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udp = ByteArray(8)
        writeU16(udp, 0, srcPort)
        writeU16(udp, 2, dstPort)
        writeU16(udp, 4, 8 + payload.size)
        // IPv4 permits a zero UDP checksum.
        val totalLength = 20 + udp.size + payload.size
        return ipHeader(srcIp, dstIp, 17, totalLength) + udp + payload
    }

    private fun buildTcp(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        options: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val headerLength = 20 + options.size
        val tcp = ByteArray(headerLength + payload.size)
        writeU16(tcp, 0, srcPort)
        writeU16(tcp, 2, dstPort)
        writeU32(tcp, 4, seq)
        writeU32(tcp, 8, ack)
        tcp[12] = ((headerLength / 4) shl 4).toByte()
        tcp[13] = flags.toByte()
        writeU16(tcp, 14, window)
        if (options.isNotEmpty()) System.arraycopy(options, 0, tcp, 20, options.size)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, tcp, headerLength, payload.size)

        val source = InetAddress.getByName(srcIp).address
        val destination = InetAddress.getByName(dstIp).address
        val pseudo = ByteArray(12)
        System.arraycopy(source, 0, pseudo, 0, 4)
        System.arraycopy(destination, 0, pseudo, 4, 4)
        pseudo[9] = 6.toByte()
        writeU16(pseudo, 10, tcp.size)
        writeU16(tcp, 16, checksum(listOf(pseudo to (0..11), tcp to (0 until tcp.size))))
        return ipHeader(srcIp, dstIp, 6, 20 + tcp.size) + tcp
    }

    private fun ipHeader(srcIp: String, dstIp: String, protocol: Int, totalLength: Int): ByteArray {
        val header = ByteArray(20)
        header[0] = 0x45
        writeU16(header, 2, totalLength)
        writeU16(header, 4, (ipId.getAndIncrement() and 0xffff).toInt())
        writeU16(header, 6, 0x4000) // Don't fragment.
        header[8] = 64
        header[9] = protocol.toByte()
        System.arraycopy(InetAddress.getByName(srcIp).address, 0, header, 12, 4)
        System.arraycopy(InetAddress.getByName(dstIp).address, 0, header, 16, 4)
        writeU16(header, 10, checksum(listOf(header to (0..19))))
        return header
    }

    private fun checksum(parts: List<Pair<ByteArray, IntRange>>): Int {
        var sum = 0L
        for ((bytes, range) in parts) {
            var i = range.first
            while (i + 1 <= range.last) {
                sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
                i += 2
            }
            if (i <= range.last) sum += (bytes[i].toInt() and 0xff) shl 8
            while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
        }
        return (sum.inv() and 0xffff).toInt()
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff

    private fun u16(bytes: ByteArray, index: Int): Int = (u8(bytes, index) shl 8) or u8(bytes, index + 1)

    private fun u32(bytes: ByteArray, index: Int): Long =
        (u8(bytes, index).toLong() shl 24) or
            (u8(bytes, index + 1).toLong() shl 16) or
            (u8(bytes, index + 2).toLong() shl 8) or
            u8(bytes, index + 3).toLong()

    private fun writeU16(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = (value ushr 8).toByte()
        bytes[index + 1] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, index: Int, value: Long) {
        bytes[index] = (value ushr 24).toByte()
        bytes[index + 1] = (value ushr 16).toByte()
        bytes[index + 2] = (value ushr 8).toByte()
        bytes[index + 3] = value.toByte()
    }

    private fun wrap(value: Long): Long = value and 0xffffffffL

    private fun relative(value: Long, isn: Long): Long =
        if (value >= isn) value - isn else value + (1L shl 32) - isn

    private fun randomBytes(): ByteArray {
        val bytes = ByteArray(4)
        java.util.Random().nextBytes(bytes)
        return bytes
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
        const val FLAG_FIN = 1
        const val FLAG_SYN = 2
        const val FLAG_RST = 4
        const val FLAG_PSH = 8
        const val FLAG_ACK = 16
        const val CONNECT_TIMEOUT_MS = 5000
        const val SOCKET_TIMEOUT_MS = 1000
        const val SYN_TIMEOUT_MS = 30_000L
        const val UDP_TIMEOUT_MS = 60_000L
        const val MAX_TCP_PAYLOAD = 1460
        const val MAX_UDP_PAYLOAD = 1472
        const val MAX_WINDOW = 1L shl 30
    }
}
