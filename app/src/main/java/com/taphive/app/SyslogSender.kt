package com.taphive.app

import android.net.Network
import android.os.Build
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class SyslogSender(private val prefs: Prefs) {
    private val queue = ArrayBlockingQueue<SyslogEvent>(QUEUE_SIZE)
    private val thread = Thread({ runLoop() }, "taphive-syslog")

    @Volatile
    private var running = true

    @Volatile
    private var dropped = 0L

    @Volatile
    private var backoffDropped = 0L

    @Volatile
    var lastError: String? = null
        private set

    private val forwarded = AtomicLong(0)

    val forwardedEvents: Long
        get() = forwarded.get()

    fun resetForwardedCount() {
        // Keep the queue: an unsent capture_stop from the previous session must still
        // be delivered. Only the forward counter is scoped to the new session.
        forwarded.set(0)
    }

    /** Network used for syslog traffic so it never enters our own VPN tunnel. */
    @Volatile
    var network: Network? = null
        set(value) {
            field = value
            closeSockets()
        }

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var retryDelayMs = 1000L
    private var nextRetryMs = 0L
    private var tokens = BUCKET_SIZE
    private var lastTokenMs = System.nanoTime()

    fun start() {
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
        closeSockets()
    }

    fun enqueue(event: SyslogEvent) {
        if (!queue.offer(event)) {
            dropped++
            lastError = "syslog queue full; dropped $dropped events"
        }
    }

    private fun runLoop() {
        while (running) {
            val event = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            if (!prefs.enabled || prefs.host.isBlank()) continue
            acquireToken()
            send(format(event))
        }
    }

    private fun acquireToken() {
        while (running) {
            val now = System.nanoTime()
            val elapsedMs = (now - lastTokenMs) / 1_000_000.0
            tokens = min(BUCKET_SIZE, tokens + elapsedMs * REFILL_PER_SECOND)
            lastTokenMs = now
            if (tokens >= 1.0) {
                tokens -= 1.0
                return
            }
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun format(event: SyslogEvent): String {
        val severity = if (event.event.startsWith("capture_")) 5 else 6
        val pri = prefs.facility * 8 + severity
        val timestamp = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date())
        val host = Build.MODEL.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val app = prefs.appName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val json = if (event.event.startsWith("capture_")) {
            event.copy(
                droppedEvents = dropped,
                // Snapshot at send time so queued flow-close events are included.
                eventsForwarded = if (event.event == "capture_stop") forwarded.get() else event.eventsForwarded
            ).toJson()
        } else {
            event.toJson()
        }
        if (prefs.syslogFormat == "rfc5424") {
            // <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG
            // Structured data is the NILVALUE; all fields stay in the versioned JSON MSG.
            val pid = Process.myPid().toString()
            val msgId = event.event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(32).ifEmpty { "-" }
            return "<$pri>1 ${UTC_MILLIS.format(event.timestamp)} " +
                "${host.take(255).ifEmpty { "-" }} ${app.take(48).ifEmpty { "-" }} " +
                "$pid $msgId - $json"
        }
        return "<$pri>$timestamp $host $app[${Process.myPid()}]: $json"
    }

    private fun send(message: String) {
        val now = System.currentTimeMillis()
        if (now < nextRetryMs) {
            backoffDropped++
            lastError = "syslog backoff active; dropped $backoffDropped events"
            return
        }
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            if (prefs.transport == "tcp") sendTcp(bytes) else sendUdp(bytes)
            forwarded.incrementAndGet()
            retryDelayMs = 1000L
            lastError = null
        } catch (e: Exception) {
            closeSockets()
            val backoff = min(MAX_BACKOFF_MS, retryDelayMs)
            retryDelayMs = min(MAX_BACKOFF_MS, backoff * 2)
            nextRetryMs = now + backoff + (Math.random() * backoff / 4).toLong()
            lastError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun sendUdp(bytes: ByteArray) {
        var socket = udpSocket
        if (socket == null || socket.isClosed) {
            socket = DatagramSocket()
            network?.bindSocket(socket)
            udpSocket = socket
        }
        socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(prefs.host, prefs.port)))
    }

    private fun sendTcp(bytes: ByteArray) {
        var socket = tcpSocket
        if (socket == null || socket.isClosed || !socket.isConnected) {
            socket = Socket()
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(prefs.host, prefs.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            tcpSocket = socket
        }
        val out = socket.getOutputStream()
        out.write(bytes)
        out.write('\n'.code)
        out.flush()
    }

    private fun closeSockets() {
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            tcpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        tcpSocket = null
    }

    private companion object {
        const val QUEUE_SIZE = 512
        const val BUCKET_SIZE = 200.0
        const val REFILL_PER_SECOND = 50.0
        const val MAX_BACKOFF_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 2000
    }
}
