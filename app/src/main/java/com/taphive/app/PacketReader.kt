package com.taphive.app

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.IOException
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

        /** Incremental direction bytes for local history, including open flows. */
        fun onFlowBytes(
            flowId: Long,
            timestampMs: Long,
            sentDelta: Long,
            receivedDelta: Long
        )

        /** Called when continuing would leave the VPN up without a working reader. */
        fun onCaptureError(message: String) {}
    }

    private class Flow(
        val id: Long,
        val key: String,
        val local: Endpoint,
        val remote: Endpoint,
        val protocol: Int,
        val openedAt: Long
    ) {
        val bytesSent = AtomicLong(0)
        val bytesReceived = AtomicLong(0)
        val packetsSent = AtomicLong(0)
        val packetsReceived = AtomicLong(0)

        @Volatile
        var lastSeen: Long = openedAt

        var sampledSent: Long = 0
        var sampledReceived: Long = 0
    }

    private val capturedPackets = AtomicLong(0)

    val packetsCaptured: Long
        get() = capturedPackets.get()

    private val flows = java.util.concurrent.ConcurrentHashMap<String, Flow>()
    private var nextFlowId = 0L
    private var input: FileInputStream? = null
    private var thread: Thread? = null
    private var lastSampleMs = 0L
    private var lastExpireMs = 0L
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val reader = try {
            FileInputStream(pfd.fileDescriptor)
        } catch (e: IOException) {
            running.set(false)
            safeEmit { sink.onCaptureError("capture input unavailable: ${e.javaClass.simpleName}") }
            return
        }
        input = reader
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
                val packet = PacketParser.parse(buffer, count)
                if (packet != null) {
                    handle(packet, count.toLong(), now)
                    try {
                        forwarder?.forward(packet, buffer, count)
                    } catch (e: Exception) {
                        // A broken forwarding session must be visible, not turn
                        // into a black hole while the VPN notification says live.
                        safeEmit {
                            sink.onCaptureError("forwarding failed: ${e.javaClass.simpleName}")
                        }
                        break
                    }
                }
                if (now - lastSampleMs >= SAMPLE_INTERVAL_MS) {
                    sampleFlows(now)
                    lastSampleMs = now
                }
                if (now - lastExpireMs >= EXPIRE_INTERVAL_MS) {
                    expire(now, false)
                    lastExpireMs = now
                }
            }
        } finally {
            expire(System.currentTimeMillis(), true)
            flows.clear()
        }
    }

    private fun handle(packet: IpPacket, length: Long, now: Long) {
        val key = PacketParser.flowKey(packet.src, packet.dst, packet.protocol)
        val flow = flows[key] ?: createFlow(key, packet, now).also { flows[key] = it }
        if (flow.local.address == packet.src.address && flow.local.port == packet.src.port) {
            flow.bytesSent.addAndGet(length)
            flow.packetsSent.incrementAndGet()
        } else {
            flow.bytesReceived.addAndGet(length)
            flow.packetsReceived.incrementAndGet()
        }
        flow.lastSeen = now
    }

    /** Accounts for payload delivered by the forwarder from the remote side. */
    fun recordInbound(packet: IpPacket, length: Int) {
        val flow = flows[PacketParser.flowKey(packet.src, packet.dst, packet.protocol)] ?: return
        flow.bytesReceived.addAndGet(length.toLong())
        flow.packetsReceived.incrementAndGet()
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
            val sent = flow.bytesSent.get()
            val received = flow.bytesReceived.get()
            val sentDelta = sent - flow.sampledSent
            val receivedDelta = received - flow.sampledReceived
            if (sentDelta > 0 || receivedDelta > 0) {
                flow.sampledSent = sent
                flow.sampledReceived = received
                safeEmit {
                    sink.onFlowBytes(flow.id, now, sentDelta, receivedDelta)
                }
            }
        }
    }

    private fun expire(now: Long, force: Boolean) {
        val iterator = flows.values.iterator()
        while (iterator.hasNext()) {
            val flow = iterator.next()
            if (!force && now - flow.lastSeen < IDLE_TIMEOUT_MS) continue
            val sent = flow.bytesSent.get()
            val received = flow.bytesReceived.get()
            val sentPending = sent - flow.sampledSent
            val receivedPending = received - flow.sampledReceived
            if (sentPending > 0 || receivedPending > 0) {
                flow.sampledSent = sent
                flow.sampledReceived = received
                safeEmit {
                    sink.onFlowBytes(flow.id, now, sentPending, receivedPending)
                }
            }
            safeEmit {
                sink.onFlowClose(
                    flow.id,
                    now,
                    flow.bytesSent.get(),
                    flow.bytesReceived.get(),
                    flow.packetsSent.get(),
                    flow.packetsReceived.get(),
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

    private companion object {
        const val BUFFER_SIZE = 65535
        const val IDLE_TIMEOUT_MS = 30_000L
        const val SAMPLE_INTERVAL_MS = 10_000L
        const val EXPIRE_INTERVAL_MS = 1_000L
        const val JOIN_TIMEOUT_MS = 2_000L
        const val POLL_TIMEOUT_MS = 250
    }
}
