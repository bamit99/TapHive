package com.taphive.app

import android.net.Network
import android.os.Build
import android.os.Process
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** What the app can actually prove about the configured syslog target. */
enum class SyslogTargetState { OFF, UNVERIFIED, VERIFIED, FAILED }

class SyslogSender(private val prefs: Prefs) {
    private val queue = ArrayBlockingQueue<SyslogEvent>(QUEUE_SIZE)
    private val thread = Thread({ runLoop() }, "taphive-syslog")

    @Volatile
    private var running = true

    private val dropped = AtomicLong(0)
    private val backoffDropped = AtomicLong(0)

    @Volatile
    var lastError: String? = null
        private set

    /** Reachability inferred from real send attempts, never from UDP assumptions. */
    @Volatile
    var targetState: SyslogTargetState = SyslogTargetState.OFF
        private set

    @Volatile
    var lastSuccessfulSendMs: Long = 0L
        private set

    /** ICMP errors arrive asynchronously, so one later send must not erase proof of failure. */
    @Volatile
    var lastFailureMs: Long = 0L
        private set

    @Volatile
    var lastFailureError: String? = null
        private set

    private val forwarded = AtomicLong(0)

    val forwardedEvents: Long
        get() = forwarded.get()

    fun resetForwardedCount() {
        // Keep the queue: an unsent capture_stop from the previous session must still
        // be delivered. Only the counters are scoped to the new session.
        forwarded.set(0)
        dropped.set(0)
        backoffDropped.set(0)
        lastError = null
    }

    /** Network used for syslog traffic so it never enters our own VPN tunnel. */
    @Volatile
    var network: Network? = null
        set(value) {
            if (field == value) return
            field = value
            resetTarget()
        }

    private var targetKey = ""

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var retryDelayMs = 1000L
    private var nextRetryMs = 0L
    private var tokens = BUCKET_SIZE
    private var lastTokenMs = System.nanoTime()

    /** Re-open sockets and forget old proof when the configured target changes. */
    private fun resetTarget() {
        closeSockets()
        retryDelayMs = 1000L
        nextRetryMs = 0L
        lastSuccessfulSendMs = 0L
        lastFailureMs = 0L
        lastFailureError = null
        lastError = null
        targetState = if (prefs.enabled) SyslogTargetState.UNVERIFIED else SyslogTargetState.OFF
    }

    private fun syncTarget() {
        val key = listOf(prefs.enabled, prefs.host, prefs.port, prefs.transport).joinToString("|")
        if (key == targetKey) return
        targetKey = key
        resetTarget()
    }

    fun start() {
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
        closeSockets()
    }

    fun enqueue(event: SyslogEvent) {
        // Do not churn the queue or surface drop warnings for a disabled target.
        if (!prefs.enabled || prefs.host.isBlank()) {
            lastError = null
            return
        }
        if (!queue.offer(event)) {
            val count = dropped.incrementAndGet()
            lastError = "syslog queue full; dropped $count events"
        }
    }

    private fun runLoop() {
        while (running) {
            val event = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            syncTarget()
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

    private fun format(event: SyslogEvent): String = SyslogFormatter.format(
        event = event,
        facility = prefs.facility,
        appName = prefs.appName,
        host = Build.MODEL,
        pid = Process.myPid(),
        format = prefs.syslogFormat,
        dropped = dropped.get(),
        forwarded = forwarded.get()
    )

    private fun send(message: String) {
        val now = System.currentTimeMillis()
        if (now < nextRetryMs) {
            val count = backoffDropped.incrementAndGet()
            if (lastFailureError == null) {
                lastError = "syslog backoff active; dropped $count events"
            }
            targetState = SyslogTargetState.FAILED
            return
        }
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            when (prefs.transport) {
                "tcp" -> sendTcp(bytes)
                "tls" -> sendTls(bytes)
                else -> sendUdp(bytes)
            }
            forwarded.incrementAndGet()
            retryDelayMs = 1000L
            nextRetryMs = 0L
            lastSuccessfulSendMs = now
            // TCP connect is positive proof. For UDP, a recent ICMP error stays red:
            // a fresh socket can accept a datagram before the next rejection arrives.
            if (prefs.transport != "udp") {
                lastFailureMs = 0L
                lastFailureError = null
                lastError = null
                targetState = SyslogTargetState.VERIFIED
            } else if (now - lastFailureMs < FAILURE_HOLD_MS) {
                lastError = lastFailureError
                targetState = SyslogTargetState.FAILED
            } else {
                lastFailureError = null
                lastError = null
                targetState = SyslogTargetState.UNVERIFIED
            }
        } catch (e: Exception) {
            closeSockets()
            val backoff = min(MAX_BACKOFF_MS, retryDelayMs)
            retryDelayMs = min(MAX_BACKOFF_MS, backoff * 2)
            nextRetryMs = now + backoff + (Math.random() * backoff / 4).toLong()
            val friendly = friendlyError(e)
            lastFailureMs = now
            lastFailureError = friendly
            lastError = friendly
            targetState = SyslogTargetState.FAILED
        }
    }

    private fun sendUdp(bytes: ByteArray) {
        var socket = udpSocket
        if (socket == null || socket.isClosed || !socket.isConnected) {
            socket = DatagramSocket()
            network?.bindSocket(socket)
            // A connected socket can surface ICMP port-unreachable on a later send,
            // which is the only negative UDP signal available without a probe packet.
            socket.connect(InetSocketAddress(prefs.host, prefs.port))
            udpSocket = socket
        }
        socket.send(DatagramPacket(bytes, bytes.size))
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

    private fun sendTls(bytes: ByteArray) {
        var socket = tlsSocket
        if (socket == null || socket.isClosed || !socket.isConnected) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = factory.createSocket() as SSLSocket
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(prefs.host, prefs.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            val params = socket.sslParameters
            // The platform default trust store plus RFC 6125 hostname checks.
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
            socket.startHandshake()
            tlsSocket = socket
        }
        val out = socket.getOutputStream()
        out.write(bytes)
        out.write('\n'.code)
        out.flush()
    }

    /** Replace raw errno text with something a user can act on. */
    private fun friendlyError(e: Exception): String {
        val message = e.message.orEmpty()
        val refused = e is ConnectException || message.contains("ECONNREFUSED") ||
            message.contains("Connection refused", ignoreCase = true)
        return when {
            e is PortUnreachableException ->
                "UDP PORT UNREACHABLE · CHECK SERVER / PORT"
            e is UnknownHostException -> "HOST NOT FOUND · CHECK SERVER"
            e is SocketTimeoutException -> "CONNECTION TIMED OUT · CHECK SERVER"
            e is SSLHandshakeException -> "TLS HANDSHAKE FAILED · CHECK CERTIFICATE"
            e is SSLException -> "TLS FAILED · CHECK CERTIFICATE"
            refused && prefs.transport == "udp" ->
                "UDP PORT UNREACHABLE · CHECK SERVER / PORT"
            refused -> "CONNECTION REFUSED · CHECK SERVER / PORT"
            else -> message.ifEmpty { e.javaClass.simpleName }
        }
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
        try {
            tlsSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        tcpSocket = null
        tlsSocket = null
    }

    private companion object {
        const val QUEUE_SIZE = 512
        const val BUCKET_SIZE = 200.0
        const val REFILL_PER_SECOND = 50.0
        const val MAX_BACKOFF_MS = 60_000L
        const val FAILURE_HOLD_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 2000
    }
}
