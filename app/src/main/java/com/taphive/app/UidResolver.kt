package com.taphive.app

import android.net.ConnectivityManager
import java.net.InetSocketAddress

class UidResolver(private val manager: ConnectivityManager) {
    fun resolve(protocol: Int, localIp: String, localPort: Int, remoteIp: String, remotePort: Int): Int =
        try {
            manager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(localIp, localPort),
                InetSocketAddress(remoteIp, remotePort)
            )
        } catch (e: Exception) {
            -1
        }
}
