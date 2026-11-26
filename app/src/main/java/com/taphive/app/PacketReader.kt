package com.taphive.app

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads IP packets from the VPN tunnel and emits flow lifecycle events.
 * Only IP headers and transport port numbers are inspected; payload bytes are discarded.
 */
class PacketReader(
    private val pfd: ParcelFileDescriptor,
    private val sink: FlowSink,
    private val forwarder: TunnelForwarder? = null,
    private val localIpv4: String = "10.0.0.2",
    private val localIpv6: String = "fd00::2"
) {
    interface FlowSink {
        fun onPacketCaptured(total: Long)

        fun onFlowOpen(
            flowId: Long,
            timestampMs: Long,
            ipVersion: Int,
            protocol: Int,
            srcIp: String,
            srcPort: Int,
            dstIp: String,
            dstPort: Int
        )

        fun onFlowClose(
            flowId: Long,
            timestampMs: Long,
            bytesSent: Long,
            bytesReceived: Long,
            packetsSent: Long,
            packetsReceived: Long,
            status: String
        )

        /** Incremental byte delta for local history, including still-open flows. */
        fun onFlowBytes(flowId: Long, timestampMs: Long, bytesDelta: Long)
    }

    private class Flow(
        val id: Long,
        val key: String,
        val local: Endpoint,
        val remote: Endpoint,
        val protocol: Int,
        val openedAt: Long
    ) {
        var bytesSent: Long = 0
        var bytesReceived: Long = 0
        var packetsSent: Long = 0
        var packetsReceived: Long = 0
        var lastSeen: Long = openedAt
        var sampledBytes: Long = 0
    }

    private val capturedPackets = AtomicLong(0)

    val packetsCaptured: Long
        get() = capturedPackets.get()

    private val flows = java.util.concurrent.ConcurrentHashMap<String, Flow>()
    private var nextFlowId = 0L
    private var input: FileInputStream? = null
    private var thread: Thread? = null
    private var lastSampleMs = 0L
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        input = FileInputStream(pfd.fileDescriptor)
        val reader = input ?: return
        thread = Thread({ loop(reader) }, "taphive-packets").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        forwarder?.stop()
        try {
            input?.close()
        } catch (_: IOException) {
        }
        try {
            thread?.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        input = null
    }

    private fun loop(reader: FileInputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        val descriptor = StructPollfd().apply {
            fd = pfd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        try {
            while (running.get()) {
                descriptor.revents = 0.toShort()
                try {
                    if (Os.poll(arrayOf(descriptor), POLL_TIMEOUT_MS) == 0) {
                        expire(System.currentTimeMillis(), false)
                        continue
                    }
                } catch (_: ErrnoException) {
                    break
                }
                val count = try {
                    reader.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (count == 0) continue
                if (count < 0) break
                capturedPackets.incrementAndGet()
                safeEmit { sink.onPacketCaptured(capturedPackets.get()) }
                val now = System.currentTimeMillis()
                val packet = parse(buffer, count)
                if (packet != null) {
                    handle(packet, count.toLong(), now)
                    forwarder?.forward(packet, buffer, count)
                }
                if (now - lastSampleMs >= SAMPLE_INTERVAL_MS) {
                    sampleFlows(now)
                    lastSampleMs = now
                }
                expire(now, false)
            }
        } finally {
            expire(System.currentTimeMillis(), true)
            flows.clear()
        }
    }

    private fun handle(packet: IpPacket, length: Long, now: Long) {
        val key = flowKey(packet.src, packet.dst, packet.protocol)
        val flow = flows[key] ?: createFlow(key, packet, now).also { flows[key] = it }
        if (flow.local.address == packet.src.address && flow.local.port == packet.src.port) {
            flow.bytesSent += length
            flow.packetsSent++
        } else {
            flow.bytesReceived += length
            flow.packetsReceived++
        }
        flow.lastSeen = now
    }

    /** Accounts for payload delivered by the forwarder from the remote side. */
    fun recordInbound(packet: IpPacket, length: Int) {
        val flow = flows[flowKey(packet.src, packet.dst, packet.protocol)] ?: return
        flow.bytesReceived += length
        flow.packetsReceived++
    }

    private fun createFlow(key: String, packet: IpPacket, now: Long): Flow {
        val local = if (packet.src.ip == localIpv4 || packet.src.ip == localIpv6) packet.src else packet.dst
        val remote = if (local === packet.src) packet.dst else packet.src
        val flow = Flow(++nextFlowId, key, local, remote, packet.protocol, now)
        safeEmit {
            sink.onFlowOpen(
                flow.id,
                now,
                if (flow.local.family == 4) 4 else 6,
                flow.protocol,
                flow.local.ip,
                flow.local.port,
                flow.remote.ip,
                flow.remote.port
            )
        }
        return flow
    }

    /** Feeds local history with deltas from flows that are still open. */
    private fun sampleFlows(now: Long) {
        for (flow in flows.values) {
            val total = flow.bytesSent + flow.bytesReceived
            val delta = total - flow.sampledBytes
            if (delta > 0) {
                flow.sampledBytes = total
                safeEmit { sink.onFlowBytes(flow.id, now, delta) }
            }
        }
    }

    private fun expire(now: Long, force: Boolean) {
        val iterator = flows.values.iterator()
        while (iterator.hasNext()) {
            val flow = iterator.next()
            if (!force && now - flow.lastSeen < IDLE_TIMEOUT_MS) continue
            val total = flow.bytesSent + flow.bytesReceived
            val pending = total - flow.sampledBytes
            if (pending > 0) {
                flow.sampledBytes = total
                safeEmit { sink.onFlowBytes(flow.id, now, pending) }
            }
            safeEmit {
                sink.onFlowClose(
                    flow.id,
                    now,
                    flow.bytesSent,
                    flow.bytesReceived,
                    flow.packetsSent,
                    flow.packetsReceived,
                    if (force) "STOPPED" else "CLOSED"
                )
            }
            iterator.remove()
        }
    }

    private fun safeEmit(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // A sink failure must not stop packet accounting.
        }
    }

    private fun parse(bytes: ByteArray, length: Int): IpPacket? {
        if (length < 1) return null
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
        var offset = 40
        var next = u8(bytes, 6)
        while (next == 0 || next == 43 || next == 44 || next == 60) {
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

    private fun flowKey(src: Endpoint, dst: Endpoint, protocol: Int): String {
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

    private fun u16(bytes: ByteArray, index: Int): Int = (u8(bytes, index) shl 8) or u8(bytes, index + 1)

    private companion object {
        const val BUFFER_SIZE = 65535
        const val IDLE_TIMEOUT_MS = 30_000L
        const val SAMPLE_INTERVAL_MS = 10_000L
        const val JOIN_TIMEOUT_MS = 2_000L
        const val POLL_TIMEOUT_MS = 250
    }
}
