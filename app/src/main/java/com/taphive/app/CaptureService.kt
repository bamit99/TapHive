package com.taphive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CaptureService : VpnService(), PacketReader.FlowSink {
    private lateinit var uidResolver: UidResolver
    private val dnsParser = DnsNameParser()
    private var pfd: ParcelFileDescriptor? = null
    private var reader: PacketReader? = null
    private var forwarder: Ipv4TunnelForwarder? = null
    private var networkCallback: NetworkCallback? = null
    private var sessionId = ""

    private val dbWriter = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1024),
        { runnable -> Thread(runnable, "taphive-db").apply { isDaemon = true } },
        { _, _ -> }
    )

    private val app: TapHiveApp
        get() = application as TapHiveApp

    override fun onCreate() {
        super.onCreate()
        uidResolver = UidResolver(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == VpnService.SERVICE_INTERFACE) super.onBind(intent) else null

    override fun onRevoke() {
        app.prefs.setCaptureWanted(false)
        starting = false
        releaseCapture(emitLifecycle = true)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                app.prefs.setCaptureWanted(true)
                startCapture()
            }
            ACTION_STOP -> stopCapture()
            ACTION_REFRESH -> if (isRunning) showForeground()
            else -> {
                // Null intent: the system recreated a killed service. Only resume
                // when the user left capture explicitly enabled.
                if (app.prefs.captureWanted) startCapture() else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (isRunning || starting) return
        starting = true

        // startForegroundService() requires startForeground() promptly. Announce
        // the service before VPN establishment, which can fail (e.g. after boot).
        showForeground()

        // Bind helper traffic to the real network before the VPN becomes the default route.
        val underlying = underlyingNetwork()
        app.sender.network = underlying
        app.geoResolver.network = underlying

        // IPv6 routing is deferred until the forwarder handles IPv6; leaving these
        // routes installed without a forwarding path would blackhole IPv6 traffic.
        val builder = Builder()
            .setSession("TapHive")
            .setMtu(1500)
            .addAddress("10.0.0.2", 30)
            .addRoute("0.0.0.0", 1)
            .addRoute("128.0.0.0", 1)
            .setMetered(false)
            // TapHive's own syslog/enrichment traffic must never re-enter the
            // tunnel it is capturing.
            .addDisallowedApplication(packageName)

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "VPN establish failed", e)
            null
        }
        if (fd == null) {
            android.util.Log.w(TAG, "VPN establish returned null")
            notifyStartFailed()
            starting = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        pfd = fd
        setUnderlyingNetworks(null)
        sessionId = UUID.randomUUID().toString()
        app.tracker.reset()
        CaptureService.packetsCaptured = 0L
        app.sender.resetForwardedCount()
        app.dnsDiagnostics.reset()
        isRunning = true
        starting = false
        app.sender.enqueue(captureEvent("capture_start", includeTotals = true))
        val tunnelForwarder = Ipv4TunnelForwarder(
            vpn = this,
            network = app.sender.network,
            tun = TunWriter(fd.fileDescriptor),
            onInbound = { packet, length -> reader?.recordInbound(packet, length) },
            onUdpPacket = { fromRemote, serverIp, remotePort, payload, length ->
                if (remotePort == 53) {
                    app.dnsDiagnostics.observe(
                        fromRemote, serverIp, remotePort, payload, length
                    )
                }
                if (fromRemote && remotePort == 53 && app.prefs.retentionHours > 0) {
                    dnsParser.parse(payload, length).forEach { (ip, name) ->
                        app.database.upsertDnsName(ip, name)
                    }
                }
            }
        )
        forwarder = tunnelForwarder
        registerNetworkCallback()
        reader = PacketReader(fd, this, tunnelForwarder).also { it.start() }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The default callback can report TapHive's own VPN. Resolve the
                // real non-VPN network instead of binding helper sockets to it.
                Networks.underlying(this@CaptureService)?.let(::applyUnderlyingNetwork)
            }

            override fun onLost(network: Network) {
                applyUnderlyingNetwork(Networks.underlying(this@CaptureService))
            }
        }
        networkCallback = callback
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun applyUnderlyingNetwork(network: Network?) {
        app.sender.network = network
        app.geoResolver.network = network
        forwarder?.updateNetwork(network)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    override fun onPacketCaptured(total: Long) {
        CaptureService.packetsCaptured = total
        app.dnsDiagnostics.expire()
    }

    override fun onFlowOpen(
        flowId: Long,
        timestampMs: Long,
        ipVersion: Int,
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ) {
        val uid = uidResolver.resolve(protocol, srcIp, srcPort, dstIp, dstPort)

        // The traffic view aggregates the same flow events the syslog path consumes.
        app.tracker.addFlow(flowId, uid, dstIp, dstPort, protocol)
        if (app.prefs.enrichmentEnabled) {
            app.geoResolver.lookup(dstIp)
        }

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

    override fun onFlowBytes(flowId: Long, timestampMs: Long, bytesDelta: Long) {
        val flow = app.tracker.openFlow(flowId) ?: return
        val id = flowId.toString()
        try {
            dbWriter.execute {
                app.database.recordFlow(
                    ts = timestampMs,
                    flowId = id,
                    uid = flow.uid,
                    ip = flow.dstIp,
                    port = flow.dstPort,
                    protocol = flow.protocol,
                    bytes = bytesDelta
                )
            }
        } catch (_: RejectedExecutionException) {
            // History is best-effort; never block the packet thread on disk.
        }
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
        app.tracker.closeFlow(flowId, bytesSent, bytesReceived, packetsSent, packetsReceived)
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

    override fun onCaptureError(message: String) {
        Handler(Looper.getMainLooper()).post {
            android.util.Log.e(TAG, "capture error: $message")
            app.prefs.setCaptureWanted(false)
            releaseCapture(emitLifecycle = false)
            stopSelf()
            notifyCaptureError()
        }
    }

    private fun underlyingNetwork(): Network? = Networks.underlying(this)

    private fun captureEvent(name: String, includeTotals: Boolean = false): SyslogEvent {
        val dns = if (includeTotals) app.dnsDiagnostics.snapshot() else null
        return SyslogEvent(
            sessionId = sessionId,
            event = name,
            packetsCaptured = if (includeTotals) CaptureService.packetsCaptured else null,
            eventsForwarded = if (includeTotals) app.sender.forwardedEvents else null,
            dnsQueries = dns?.queries,
            dnsResponses = dns?.responses,
            dnsTimeouts = dns?.timeouts,
            dnsNxdomain = dns?.nxdomain,
            dnsServfail = dns?.servfail,
            dnsOtherErrors = dns?.otherErrors,
            dnsMalformed = dns?.malformed
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    /** Auto-start after a reboot can fail if VPN approval was reset. */
    private fun notifyStartFailed() {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.capture_start_failed_title))
            .setContentText(getString(R.string.capture_start_failed_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun notifyCaptureError() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 3, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.capture_failed_title))
            .setContentText(getString(R.string.capture_failed_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun showForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val forwarding = app.prefs.enabled && app.prefs.host.isNotBlank()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    if (forwarding) R.string.notification_text
                    else R.string.notification_text_local
                )
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {
        app.prefs.setCaptureWanted(false)
        starting = false
        releaseCapture(emitLifecycle = true)
        stopSelf()
    }

    private fun releaseCapture(emitLifecycle: Boolean) {
        synchronized(this) {
            if (!isRunning && reader == null && pfd == null) return
            reader?.stop()
            CaptureService.packetsCaptured = reader?.packetsCaptured ?: CaptureService.packetsCaptured
            reader = null
            unregisterNetworkCallback()
            forwarder = null
            if (emitLifecycle && isRunning) {
                app.sender.enqueue(captureEvent("capture_stop", includeTotals = true))
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            pfd = null
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        releaseCapture(emitLifecycle = true)
        dbWriter.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TapHiveCapture"
        const val ACTION_START = "com.taphive.app.START"
        const val ACTION_STOP = "com.taphive.app.STOP"
        const val ACTION_REFRESH = "com.taphive.app.REFRESH"
        private const val CHANNEL_ID = "taphive_capture"
        private const val ALERT_CHANNEL_ID = "taphive_alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Background start denied; the user can start it from the app.
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }

        fun refreshNotification(context: Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, CaptureService::class.java).setAction(ACTION_REFRESH)
                )
            } catch (_: Exception) {
                // Foreground settings screen; if the service cannot be reached,
                // the notification refreshes on the next capture restart.
            }
        }

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var starting = false
            private set

        @Volatile
        var packetsCaptured: Long = 0L
            private set
    }
}
