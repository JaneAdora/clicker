package com.janeadora.clicker.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

data class FoundTv(val serviceName: String, val host: String)

/** Discovery could not start (refused by the system, or NsdManager reported a failure). */
class DiscoveryFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The address to dial: the first IPv4, else the first IPv6 that is not link-local, else null. */
internal fun pickHost(addresses: List<InetAddress>): String? =
    (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress })
        ?.hostAddress

/**
 * Finds Android TVs on the local network with NsdManager. Discovery is
 * unauthenticated: a found TV is only a name and an address, never proof
 * of which TV it is.
 */
class Discovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main: Executor = ContextCompat.getMainExecutor(context.applicationContext)

    /**
     * The TVs found so far, sorted by name, starting with an empty list.
     * Browsing runs while the flow is collected. Cancelling it, or any
     * failure, stops discovery and drops every callback it registered. A
     * search that cannot start ends the flow with [DiscoveryFailedException].
     * Logs carry counts and error codes only, never names or addresses.
     */
    fun browse(): Flow<List<FoundTv>> = callbackFlow {
        // Touched only on the main thread: NsdManager callbacks are posted there.
        val found = HashMap<String, String>()
        var stopped = false

        fun publish() {
            trySend(found.map { (name, host) -> FoundTv(name, host) }.sortedBy { it.serviceName.lowercase() })
        }

        fun onResolved(name: String, host: String?) {
            if (stopped) return
            if (host == null) found.remove(name) else found[name] = host
            publish()
        }

        val resolver: Resolver = if (Build.VERSION.SDK_INT >= 34) {
            InfoCallbacks(nsd, main, ::onResolved)
        } else {
            SerialResolver(nsd, main, ::onResolved)
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.execute { if (!stopped) resolver.add(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.execute {
                    if (stopped) return@execute
                    resolver.remove(info.serviceName)
                    onResolved(info.serviceName, null)
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(LOG_TAG, "discovery failed to start: $errorCode")
                close(DiscoveryFailedException("start failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(LOG_TAG, "discovery failed to stop: $errorCode")
            }
        }

        publish()
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            // A refused request (no permission, a bad argument) ends the flow, cleanup included.
            Log.w(LOG_TAG, "discovery refused: ${e.javaClass.simpleName}")
            close(DiscoveryFailedException("refused", e))
        }
        awaitClose {
            stopped = true
            resolver.stopAll()
            // Throws when the listener is not registered (start failed); nothing is left then.
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }.flowOn(Dispatchers.Main)

    private interface Resolver {
        fun add(info: NsdServiceInfo)
        fun remove(name: String)
        fun stopAll()
    }

    /**
     * API 34 and later: one ServiceInfoCallback per found TV, which also
     * reports a later address change. Each is unregistered when the TV is
     * lost and when browsing stops.
     */
    @RequiresApi(34)
    private class InfoCallbacks(
        private val nsd: NsdManager,
        private val main: Executor,
        private val onResolved: (String, String?) -> Unit,
    ) : Resolver {
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        override fun add(info: NsdServiceInfo) {
            val name = info.serviceName
            if (name in callbacks) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    if (callbacks[name] === this) onResolved(name, pickHost(updated.hostAddresses))
                }

                override fun onServiceLost() {
                    if (callbacks[name] === this) {
                        remove(name)
                        onResolved(name, null)
                    }
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(LOG_TAG, "resolve failed: $errorCode")
                    if (callbacks[name] === this) callbacks.remove(name)
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[name] = callback
            try {
                nsd.registerServiceInfoCallback(info, main, callback)
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "resolve refused: ${e.javaClass.simpleName}")
                callbacks.remove(name)
            }
        }

        override fun remove(name: String) {
            callbacks.remove(name)?.let { unregister(it) }
        }

        override fun stopAll() {
            callbacks.values.forEach(::unregister)
            callbacks.clear()
        }

        // Throws when the callback is no longer registered; then nothing is left to drop.
        private fun unregister(callback: NsdManager.ServiceInfoCallback) {
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
        }
    }

    /**
     * Before API 34: resolveService, which cannot run two resolves at once,
     * so found TVs wait in a queue and are resolved one at a time. A resolve
     * cannot be cancelled on these versions; its listener is dropped by the
     * system once it reports, and a late answer after stopAll is ignored.
     */
    private class SerialResolver(
        private val nsd: NsdManager,
        private val main: Executor,
        private val onResolved: (String, String?) -> Unit,
    ) : Resolver {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false
        private var stopped = false

        // The TV being resolved now, cleared if it is lost meanwhile.
        private var current: String? = null

        override fun add(info: NsdServiceInfo) {
            if (queue.none { it.serviceName == info.serviceName }) queue.addLast(info)
            next()
        }

        override fun remove(name: String) {
            queue.removeAll { it.serviceName == name }
            if (current == name) current = null
        }

        override fun stopAll() {
            stopped = true
            current = null
            queue.clear()
        }

        private fun next() {
            if (busy || stopped) return
            val info = queue.removeFirstOrNull() ?: return
            busy = true
            current = info.serviceName
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION") // getHost is the only address before API 34.
                    val host = resolved.host?.let { pickHost(listOf(it)) }
                    main.execute { done { if (host != null && current == info.serviceName) onResolved(info.serviceName, host) } }
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    Log.w(LOG_TAG, "resolve failed: $errorCode")
                    main.execute { done {} }
                }
            }
            try {
                @Suppress("DEPRECATION") // Replaced by registerServiceInfoCallback on API 34, used there.
                nsd.resolveService(info, listener)
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "resolve refused: ${e.javaClass.simpleName}")
                busy = false
                current = null
                next()
            }
        }

        private inline fun done(report: () -> Unit) {
            busy = false
            if (stopped) return
            report()
            current = null
            next()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
