package com.taphive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.time.Instant
import java.util.UUID

class CaptureService : VpnService(), PacketReader.FlowSink {
    private lateinit var uidResolver: UidResolver
    private var pfd: ParcelFileDescriptor? = null
    private var reader: PacketReader? = null
    private var sessionId = ""

    private val app: TapHiveApp
        get() = application as TapHiveApp

    override fun onCreate() {
        super.onCreate()
        uidResolver = UidResolver(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (isRunning) return

        // Bind syslog traffic to the real network before the VPN becomes the default route.
        app.sender.network = underlyingNetwork()

        val builder = Builder()
            .setSession("TapHive")
            .setMtu(1500)
            .addAddress("10.0.0.2", 30)
            .addRoute("0.0.0.0", 1)
            .addRoute("128.0.0.0", 1)
            .addAddress("fd00::2", 64)
            .addRoute("2000::", 3)
            .addRoute("fc00::", 7)
            .setMetered(false)

        val fd = try {
            builder.establish()
        } catch (_: Exception) {
            stopSelf()
            return
        }
        if (fd == null) {
            stopSelf()
            return
        }

        pfd = fd
        sessionId = UUID.randomUUID().toString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        isRunning = true
        app.sender.enqueue(captureEvent("capture_start"))
        reader = PacketReader(fd, this).also { it.start() }
    }

    override fun onFlowOpen(
        flowId: Long,
        timestampMs: Long,
        ipVersion: Int,
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        srcIsLocal: Boolean
    ) {
        val localIp = if (srcIsLocal) srcIp else dstIp
        val localPort = if (srcIsLocal) srcPort else dstPort
        val remoteIp = if (srcIsLocal) dstIp else srcIp
        val remotePort = if (srcIsLocal) dstPort else srcPort
        val uid = uidResolver.resolve(protocol, localIp, localPort, remoteIp, remotePort)

        app.sender.enqueue(
            SyslogEvent(
                sessionId = sessionId,
                event = "flow_open",
                timestamp = Instant.ofEpochMilli(timestampMs),
                flowId = flowId.toString(),
                uid = uid,
                ipVersion = ipVersion,
                protocol = protocol,
                srcIp = srcIp,
                srcPort = srcPort,
                dstIp = dstIp,
                dstPort = dstPort
            )
        )
    }

    override fun onFlowClose(
        flowId: Long,
        timestampMs: Long,
        bytesSent: Long,
        bytesReceived: Long,
        packetsSent: Long,
        packetsReceived: Long,
        status: String
    ) {
        app.sender.enqueue(
            SyslogEvent(
                sessionId = sessionId,
                event = "flow_close",
                timestamp = Instant.ofEpochMilli(timestampMs),
                flowId = flowId.toString(),
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                packetsSent = packetsSent,
                packetsReceived = packetsReceived,
                status = status
            )
        )
    }

    private fun underlyingNetwork(): Network? {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        }
    }

    private fun captureEvent(name: String) = SyslogEvent(sessionId = sessionId, event = name)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {
        releaseCapture(emitLifecycle = true)
        stopSelf()
    }

    private fun releaseCapture(emitLifecycle: Boolean) {
        reader?.stop()
        reader = null
        if (emitLifecycle && isRunning) {
            app.sender.enqueue(captureEvent("capture_stop"))
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        releaseCapture(emitLifecycle = true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.taphive.app.START"
        const val ACTION_STOP = "com.taphive.app.STOP"
        private const val CHANNEL_ID = "taphive_capture"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }
}
