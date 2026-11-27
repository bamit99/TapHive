package com.taphive.app

/**
 * In-memory aggregation of live flows, feeding the traffic view.
 *
 * Events arrive on the packet-reading thread; the UI reads snapshots on the main
 * thread, so every public method is guarded by `synchronized(this)`.
 */
class TrafficTracker {
    /** Metadata captured when a flow opens; merged in when it closes. */
    data class OpenFlow(
        val uid: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int
    )

    /** One row of the top-destinations list. */
    data class DestinationRow(
        val ip: String,
        val port: Int,
        val protocol: Int,
        val uid: Int,
        val bytes: Long,
        val flows: Long
    )

    private val openFlows = HashMap<Long, OpenFlow>()
    private val appTotals = HashMap<Int, AppTotals>()
    private val ipTotals = HashMap<String, IpTotals>()

    private class AppTotals(var bytes: Long = 0, var flows: Long = 0)
    private class IpTotals(
        var bytes: Long = 0,
        var flows: Long = 0,
        var uid: Int = -1,
        val dstPort: Int,
        val protocol: Int
    )

    fun addFlow(flowId: Long, uid: Int, dstIp: String, dstPort: Int, protocol: Int) =
        synchronized(this) {
            openFlows[flowId] = OpenFlow(uid, dstIp, dstPort, protocol)
        }

    fun openFlow(flowId: Long): OpenFlow? = synchronized(this) { openFlows[flowId] }

    fun closeFlow(
        flowId: Long,
        bytesSent: Long,
        bytesReceived: Long,
        packetsSent: Long,
        packetsReceived: Long
    ): OpenFlow? = synchronized(this) {
        val flow = openFlows.remove(flowId) ?: return@synchronized null
        val bytes = bytesSent + bytesReceived
        val ip = ipTotals.getOrPut(flow.dstIp) { IpTotals(0, 0, flow.uid, flow.dstPort, flow.protocol) }
        ip.bytes += bytes
        ip.flows += 1
        ip.uid = flow.uid
        val app = appTotals.getOrPut(flow.uid) { AppTotals() }
        app.bytes += bytes
        app.flows += 1
        flow
    }

    fun reset() = synchronized(this) {
        openFlows.clear()
        appTotals.clear()
        ipTotals.clear()
    }

    /** uid -> total bytes, largest first. */
    fun appShares(): List<Pair<Int, Long>> = synchronized(this) {
        appTotals.entries
            .map { it.key to it.value.bytes }
            .filter { it.second > 0L }
            .sortedByDescending { it.second }
    }

    /** dstIp -> total bytes, largest first. */
    fun ipShares(): List<Pair<String, Long>> = synchronized(this) {
        ipTotals.entries
            .map { it.key to it.value.bytes }
            .filter { it.second > 0L }
            .sortedByDescending { it.second }
    }

    fun topDestinations(limit: Int = 10): List<DestinationRow> = synchronized(this) {
        ipTotals.entries
            .map { (ip, t) -> DestinationRow(ip, t.dstPort, t.protocol, t.uid, t.bytes, t.flows) }
            .filter { it.bytes > 0L }
            .sortedByDescending { it.bytes }
            .take(limit)
    }

    /** Flows that are currently open (live connections). */
    val liveFlowCount: Int
        get() = synchronized(this) { openFlows.size }
}
