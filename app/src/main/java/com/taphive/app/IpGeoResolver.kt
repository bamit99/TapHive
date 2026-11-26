package com.taphive.app

import android.net.Network
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Optional destination-IP enrichment over HTTPS.
 *
 * Only called when the user enables external enrichment. The provider receives
 * the destination IP only; UID, ports, DNS names and local history stay on-device.
 */
class IpGeoResolver(
    private val database: TrafficDatabase
) {
    companion object {
        private const val BASE_URL = "https://ipwho.is/"
        private const val TIMEOUT_MS = 5000
        private const val MIN_INTERVAL_MS = 1500L
        private const val TTL_MS = 6 * 60 * 60 * 1000L
        private const val RETRY_AFTER_MS = 5 * 60 * 1000L
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_CACHE = 2048
        private const val LOCAL_LABEL = "LOCAL"
    }

    private class HttpResult(val body: String, val delayMs: Long)

    /** Network used for lookups so they never enter our own VPN tunnel. */
    @Volatile
    var network: Network? = null

    private val resolvedAt = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > MAX_CACHE
        }
    )

    private val retryAt = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > MAX_CACHE
        }
    )

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "taphive-geo").apply { isDaemon = true } },
        { _, _ -> }
    )

    @Volatile
    private var lastRequestMs = 0L

    @Volatile
    private var nextRequestMs = 0L

    fun lookup(ip: String) {
        if (ip.isBlank()) return
        if (isLocal(ip)) {
            database.upsertGeo(ip, LOCAL_LABEL, null)
            return
        }
        val now = System.currentTimeMillis()
        val resolved = resolvedAt[ip] ?: 0L
        if (now - resolved < TTL_MS) return
        if (now < (retryAt[ip] ?: 0L)) return
        try {
            executor.execute { resolve(ip) }
        } catch (_: RejectedExecutionException) {
            // Bounded queue is full; retry after a short delay rather than
            // suppressing this IP for the full success TTL.
            retryAt[ip] = now + RETRY_AFTER_MS
        }
    }

    private fun resolve(ip: String) {
        waitForSlot()
        try {
            val result = fetch(ip)
            if (result.delayMs > 0) {
                nextRequestMs = max(nextRequestMs, System.currentTimeMillis() + result.delayMs)
            }
            val json = JSONObject(result.body)
            if (!json.optBoolean("success", false)) {
                // The provider has no record for this address; do not hammer it.
                resolvedAt[ip] = System.currentTimeMillis()
                retryAt.remove(ip)
                return
            }
            val country = json.optString("country").ifBlank { null }
            val connection = json.optJSONObject("connection")
            val org = connection?.let {
                sequenceOf(it.optString("org"), it.optString("isp"))
                    .firstOrNull { value -> value.isNotBlank() }
                    ?: it.optInt("asn", 0).takeIf { asn -> asn > 0 }?.let { asn -> "AS$asn" }
            }
            if (country != null || org != null) {
                database.upsertGeo(ip, country, org)
                resolvedAt[ip] = System.currentTimeMillis()
                retryAt.remove(ip)
            } else {
                retryAt[ip] = System.currentTimeMillis() + RETRY_AFTER_MS
            }
        } catch (_: Exception) {
            // Offline, rate-limited or a timed-out lookup: retry later instead
            // of hiding the country for the full success TTL.
            retryAt[ip] = System.currentTimeMillis() + RETRY_AFTER_MS
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
        val url = URL(BASE_URL + ip + "?fields=success,country,connection")
        val connection = (network?.openConnection(url) ?: url.openConnection())
            as? HttpsURLConnection ?: throw IOException("enrichment requires HTTPS")
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "TapHive/1.1")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) {
                throw IOException("enrichment HTTP $status")
            }
            val body = connection.inputStream.use(::readLimited)
            val retryAfterMs = connection.getHeaderField("Retry-After")
                ?.toLongOrNull()
                ?.times(1000L)
                ?: 0L
            return HttpResult(body, retryAfterMs)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) throw IOException("enrichment response too large")
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun isLocal(ip: String): Boolean {
        val address = try {
            InetAddress.getByName(ip)
        } catch (_: Exception) {
            return false
        }
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true

        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return (a == 100 && b in 64..127) ||
                (a == 192 && b == 0) ||
                (a == 198 && (b == 18 || b == 19)) ||
                a >= 240
        }
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            return (first and 0xfe) == 0xfc // fc00::/7 ULA
        }
        return false
    }
}
