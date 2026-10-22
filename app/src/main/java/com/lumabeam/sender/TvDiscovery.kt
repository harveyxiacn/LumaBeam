package com.lumabeam.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lumabeam.core.Net
import java.util.ArrayDeque

data class TvTarget(val name: String, val host: String, val port: Int)

/**
 * Finds LumaBeam TVs on the LAN via DNS-SD. The legacy NSD API
 * resolves one service at a time, so found services are queued.
 */
class TvDiscovery(context: Context, private val onChanged: (List<TvTarget>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, TvTarget>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discovery failed: $errorCode")
            main.post { discovering = false }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            main.post {
                pending.addLast(info)
                resolveNext()
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            main.post {
                if (found.remove(info.serviceName) != null) onChanged(found.values.toList())
            }
        }
    }

    fun start() {
        if (discovering) return
        try {
            nsd.discoverServices(Net.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discovering = true
        } catch (e: Exception) {
            Log.w(TAG, "cannot discover: ${e.message}")
        }
    }

    fun stop() {
        if (!discovering) return
        discovering = false
        try {
            nsd.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
        }
        pending.clear()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = pending.pollFirst() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val host = serviceInfo.host?.hostAddress
                    if (host != null && discovering) {
                        found[serviceInfo.serviceName] = TvTarget(serviceInfo.serviceName, host, serviceInfo.port)
                        onChanged(found.values.toList())
                    }
                    resolveNext()
                }
            }
        })
    }

    companion object {
        private const val TAG = "TvDiscovery"
    }
}
