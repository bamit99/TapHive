package com.taphive.app

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregate DNS health counters for plaintext UDP DNS on remote port 53.
 * Queries are tracked by server + transaction id only for timeout accounting;
 * qnames are never stored or emitted. TCP DNS and DoH/DoT are not visible here.
 */
class DnsDiagnostics(private val parser: DnsNameParser) {
    data class Snapshot(
        val queries: Long,
        val responses: Long,
        val timeouts: Long,
        val nxdomain: Long,
        val servfail: Long,
        val otherErrors: Long,
        val malformed: Long
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, Long>()

    private val queries = AtomicLong(0)
    private val responses = AtomicLong(0)
    private val timeouts = AtomicLong(0)
    private val nxdomain = AtomicLong(0)
    private val servfail = AtomicLong(0)
    private val otherErrors = AtomicLong(0)
    private val malformed = AtomicLong(0)

    @Volatile
    private var lastExpireMs = 0L

    fun reset() {
        synchronized(lock) { pending.clear() }
        queries.set(0)
        responses.set(0)
        timeouts.set(0)
        nxdomain.set(0)
        servfail.set(0)
        otherErrors.set(0)
        malformed.set(0)
        lastExpireMs = 0L
    }

    fun observe(
        fromRemote: Boolean,
        serverIp: String,
        serverPort: Int,
        payload: ByteArray,
        length: Int
    ) {
        // Diagnostics are intentionally limited to plaintext DNS over UDP.
        if (serverPort != DNS_PORT) return
        if (length < 12) {
            malformed.incrementAndGet()
            return
        }
        val now = System.currentTimeMillis()
        if (!fromRemote) {
            val query = parser.parseQuery(payload, length)
            if (query == null) {
                malformed.incrementAndGet()
                return
            }
            queries.incrementAndGet()
            synchronized(lock) {
                pending["$serverIp|${query.id}"] = now
                if (pending.size > MAX_PENDING) {
                    val oldest = pending.entries.iterator()
                    if (oldest.hasNext()) {
                        oldest.next()
                        oldest.remove()
                        timeouts.incrementAndGet()
                    }
                }
                expireLocked(now)
            }
        } else {
            responses.incrementAndGet()
            when (parser.responseCode(payload, length)) {
                0 -> Unit
                2 -> servfail.incrementAndGet()
                3 -> nxdomain.incrementAndGet()
                null -> malformed.incrementAndGet()
                else -> otherErrors.incrementAndGet()
            }
            synchronized(lock) {
                pending.remove("$serverIp|${u16(payload, 0)}")
                expireLocked(now)
            }
        }
    }

    fun expire(now: Long = System.currentTimeMillis()) {
        if (now - lastExpireMs < 1_000L) return
        lastExpireMs = now
        synchronized(lock) { expireLocked(now) }
    }

    private fun expireLocked(now: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > DNS_TIMEOUT_MS) {
                iterator.remove()
                timeouts.incrementAndGet()
            }
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        queries = queries.get(),
        responses = responses.get(),
        timeouts = timeouts.get(),
        nxdomain = nxdomain.get(),
        servfail = servfail.get(),
        otherErrors = otherErrors.get(),
        malformed = malformed.get()
    )

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)

    private companion object {
        const val MAX_PENDING = 512
        const val DNS_TIMEOUT_MS = 5_000L
        const val DNS_PORT = 53
    }
}
