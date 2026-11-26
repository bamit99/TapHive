package com.taphive.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Finds the real network so helper traffic never enters our own VPN tunnel. */
internal object Networks {
    fun underlying(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun usable(network: Network?): Boolean {
            val caps = network?.let(manager::getNetworkCapabilities) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        }
        manager.activeNetwork?.takeIf(::usable)?.let { return it }
        return manager.allNetworks.firstOrNull(::usable)
    }
}
