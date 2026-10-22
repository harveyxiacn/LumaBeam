package com.lumabeam.core

import android.content.Context
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

/** Network and identity helpers shared by the receiver and the sender. */
object Net {
    const val MIRROR_PORT = 47800
    const val HTTP_PORT = 47801
    const val NSD_TYPE = "_lumabeam._tcp."

    private const val PREFS = "lumabeam"

    /** Best guess at this device's LAN IPv4 address: prefers Wi-Fi/Ethernet over VPN and mobile links. */
    fun localIpv4(): Inet4Address? {
        val candidates = mutableListOf<Pair<Int, Inet4Address>>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            val rank = when {
                name.startsWith("eth") -> 0
                name.startsWith("wlan") -> 1
                name.startsWith("ap") || name.startsWith("swlan") -> 2
                name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") -> 9
                else -> 5
            }
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val bonus = if (addr.isSiteLocalAddress) 0 else 3
                    candidates += (rank + bonus) to addr
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    fun interfaceFor(addr: InetAddress): NetworkInterface? = try {
        NetworkInterface.getByInetAddress(addr)
    } catch (e: Exception) {
        null
    }

    fun deviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("device_name", null) ?: defaultName()
    }

    fun setDeviceName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("device_name", name.trim().ifEmpty { defaultName() }).apply()
    }

    private fun defaultName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) "LumaBeam TV" else "LumaBeam ($model)"
    }

    /** Stable UPnP UUID for this install. */
    fun deviceUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("uuid", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("uuid", id).apply()
        return id
    }
}
