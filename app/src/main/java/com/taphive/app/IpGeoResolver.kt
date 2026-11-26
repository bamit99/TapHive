package com.taphive.app

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Resolves destination IPs to a country and an organisation label via the
 * free ip-api.com tier. No API key, no new dependency.
 *
 * A raw socket is used so the request can be bound to the underlying network
 * (the VPN tunnel must not swallow it) and so we can honour the tier's rate
 * limit headers. Results are persisted in [TrafficDatabase], so a lookup that
 * finishes after a flow closes still enriches history.
 */
class IpGeoResolver(
    private val context: Context,
    private val database: TrafficDatabase
) {
    companion object {
        const val API_BASE = "http://ip-api.com/json/"
        const val TTL_MS = 6 * 60 * 60 * 1000L
        private const val HOST = "ip-api.com"
        private const val PORT = 80
        private const val FIELDS = "status,message,country,org,isp,as"
        private const val TIMEOUT_MS = 5000
        // Free tier allows 45 requests/minute for single lookups.
        private const val MIN_INTERVAL_MS = 1400L
        private const val RATE_LIMIT_MS = 60_000L
        private const val LOCAL_LABEL = "LOCAL"
    }

    private class HttpResult(val body: String, val delayMs: Long)

    /** Network used for lookups so they never enter our own VPN tunnel. */
    @Volatile
    var network: Network? = null

    /** In-memory mirror so the UI can show a country before the next DB read. */
    val countries: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    private val executor = Executors.newSingleThreadExecutor()
    private val attemptedAt = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastRequestMs = 0L
    @Volatile
    private var nextRequestMs = 0L

    fun lookup(ip: String) {
        if (ip.isBlank()) return
        if (isLocal(ip)) {
            countries[ip] = LOCAL_LABEL
            database.upsertGeo(ip, LOCAL_LABEL, null)
            return
        }
        val now = System.currentTimeMillis()
        if (now - (attemptedAt[ip] ?: 0L) < TTL_MS) return
        attemptedAt[ip] = now
        executor.execute { resolve(ip) }
    }

    private fun resolve(ip: String) {
        waitForSlot()
        try {
            val result = fetch(ip)
            if (result.delayMs > 0) {
                nextRequestMs = max(nextRequestMs, System.currentTimeMillis() + result.delayMs)
            }
            val json = JSONObject(result.body)
            if (json.optString("status") != "success") {
                if (json.optString("message").contains("limit", ignoreCase = true)) {
                    nextRequestMs = System.currentTimeMillis() + RATE_LIMIT_MS
                }
                return
            }
            val country = json.optString("country").ifBlank { null }
            val org = listOf("org", "isp", "as")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() }
            if (country != null) countries[ip] = country
            database.upsertGeo(ip, country, org)
        } catch (_: Exception) {
            // Offline, rate-limited or a non-public IP: keep the raw IP for now.
        }
    }

    private fun waitForSlot() {
        while (true) {
            val now = System.currentTimeMillis()
            val wait = max(nextRequestMs, lastRequestMs + MIN_INTERVAL_MS) - now
            if (wait <= 0L) {
                lastRequestMs = now
                return
            }
            try {
                Thread.sleep(min(wait, 5_000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun fetch(ip: String): HttpResult {
        val socket = Socket()
        try {
            (network ?: Networks.underlying(context))?.bindSocket(socket)
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val request = "GET /json/$ip?fields=$FIELDS HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "User-Agent: TapHive/1.0\r\n" +
                "Accept: application/json\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val text = String(socket.getInputStream().readBytes(), Charsets.UTF_8)
            val split = text.indexOf("\r\n\r\n")
            val head = if (split >= 0) text.substring(0, split) else ""
            val body = if (split >= 0) text.substring(split + 4) else text
            val lines = head.split("\r\n")
            val status = lines.firstOrNull()
                ?.substringAfter(" ", "")
                ?.substringBefore(" ")
                ?: ""
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            val remaining = headers["x-rl"]?.toIntOrNull() ?: 45
            val ttlSeconds = headers["x-ttl"]?.toLongOrNull() ?: 60L
            val delay = if (remaining <= 0 || status == "429") ttlSeconds * 1000L else 0L
            if (status != "200") throw IOException("ip-api HTTP $status")
            return HttpResult(body, delay)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun isLocal(ip: String): Boolean {
        if (ip == "::1" || ip.startsWith("fe80") || ip.startsWith("fc") || ip.startsWith("fd")) return true
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 0 || a == 10 || a == 127 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }
}
