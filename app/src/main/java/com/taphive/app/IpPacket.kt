package com.taphive.app

/** Direction-independent endpoint as seen by the VPN client. */
data class Endpoint(
    val family: Int,
    val address: String,
    val ip: String,
    val port: Int
)

/** Minimal parsed IP packet shared by the reader and the forwarding path. */
data class IpPacket(
    val protocol: Int,
    val src: Endpoint,
    val dst: Endpoint
)

fun endpointOf(ip: String, port: Int, family: Int = 4): Endpoint {
    val bytes = java.net.InetAddress.getByName(ip).address
    return Endpoint(
        family = family,
        address = String(bytes, Charsets.ISO_8859_1),
        ip = ip,
        port = port
    )
}
