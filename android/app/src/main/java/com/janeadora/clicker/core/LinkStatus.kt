package com.janeadora.clicker.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * The link the screen shows. With no Wi-Fi (or Ethernet) the TV cannot be
 * reached, so a link that is idle, on the way or unreachable reads as NoWifi.
 */
fun linkFor(wifiConnected: Boolean, base: Link): Link = when {
    wifiConnected -> base
    base == Link.Idle || base == Link.Connecting || base == Link.Unreachable -> Link.NoWifi
    else -> base
}

/** False when the link is already up or on its way, so a new connect would only restart it. */
fun wantsConnect(link: Link): Boolean = link != Link.Connected && link != Link.Connecting && link != Link.Pairing

/**
 * Whether the phone has a Wi-Fi or Ethernet network, the only ways to reach
 * a TV on the LAN. Process-scoped, next to the repository. The system
 * callback is registered only while someone collects [wifiConnected] (with a
 * grace period so a rotation does not re-register), and unregistered after.
 * Each registration first re-reads the system, and [refresh] does the same
 * on demand, so a screen coming back never starts from a stale value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiMonitor(context: Context, scope: CoroutineScope) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(isConnected())

    val wifiConnected: StateFlow<Boolean> = state.asStateFlow()

    /** Asks the system now; does not depend on anyone collecting. */
    fun isConnected(): Boolean {
        @Suppress("DEPRECATION") // allNetworks also sees a Wi-Fi that is not the default network.
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.isLan() == true }
    }

    /** Re-reads the system into [wifiConnected]. */
    fun refresh() {
        state.value = isConnected()
    }

    init {
        scope.launch {
            state.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .transformLatest { active ->
                    if (!active) delay(UNREGISTER_AFTER_MS)
                    emit(active)
                }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) listen() }
        }
    }

    // Registers the callback and holds it until cancelled.
    private suspend fun listen() {
        val up = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(up) { up.add(network) }
                state.value = true
            }

            override fun onLost(network: Network) {
                state.value = synchronized(up) { up.remove(network); up.isNotEmpty() }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // A Wi-Fi with no internet still reaches the TV.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        refresh()
        try {
            awaitCancellation()
        } finally {
            cm.unregisterNetworkCallback(callback)
        }
    }

    private fun NetworkCapabilities.isLan() =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    private companion object {
        const val UNREGISTER_AFTER_MS = 5_000L
    }
}
