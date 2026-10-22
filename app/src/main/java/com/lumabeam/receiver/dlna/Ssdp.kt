package com.lumabeam.receiver.dlna

import android.util.Log
import com.lumabeam.core.Net
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SSDP (UPnP discovery): answers M-SEARCH queries from control points and
 * periodically multicasts NOTIFY ssdp:alive so phones find the renderer.
 */
class Ssdp(private val uuid: String, private val httpPort: Int) {
    private val group: InetAddress = InetAddress.getByName("239.255.255.250")
    private var socket: MulticastSocket? = null
    @Volatile private var running = false

    private val targets = listOf(
        "upnp:rootdevice",
        "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
    )

    fun start() {
        val s = MulticastSocket(null as SocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(1900))
        s.timeToLive = 4
        val local = Net.localIpv4()
        val nif = local?.let { Net.interfaceFor(it) }
        try {
            if (nif != null) {
                s.networkInterface = nif
                s.joinGroup(InetSocketAddress(group, 1900), nif)
            } else {
                @Suppress("DEPRECATION")
                s.joinGroup(group)
            }
        } catch (e: Exception) {
            Log.w(TAG, "joinGroup failed: ${e.message}")
        }
        socket = s
        running = true
        Thread(::receiveLoop, "ssdp-receive").start()
        Thread(::announceLoop, "ssdp-announce").start()
    }

    fun stop() {
        running = false
        try {
            repeat(2) { for (t in targets) sendNotify(t, "ssdp:byebye") }
        } catch (_: Exception) {
        }
        socket?.close()
    }

    private fun location(): String? {
        val ip = Net.localIpv4()?.hostAddress ?: return null
        return "http://$ip:$httpPort/dlna/description.xml"
    }

    private fun usn(target: String) = if (target.startsWith("uuid:")) target else "uuid:$uuid::$target"

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket?.receive(packet) ?: break
            } catch (e: Exception) {
                if (running) Log.w(TAG, "receive failed: ${e.message}")
                break
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
            val headers = parseHeaders(text)
            if (!headers["man"].orEmpty().contains("ssdp:discover", ignoreCase = true)) continue
            val st = headers["st"]?.trim() ?: continue
            val matches = when {
                st.equals("ssdp:all", ignoreCase = true) -> targets
                targets.any { it.equals(st, ignoreCase = true) } -> listOf(st)
                // Accept requests for later versions of the renderer device type.
                st.startsWith("urn:schemas-upnp-org:device:MediaRenderer:", ignoreCase = true) -> listOf(st)
                else -> emptyList()
            }
            for (target in matches) respond(target, packet.address, packet.port)
        }
    }

    private fun respond(target: String, address: InetAddress, port: Int) {
        val loc = location() ?: return
        val msg = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "DATE: ${httpDate()}\r\n" +
            "EXT:\r\n" +
            "LOCATION: $loc\r\n" +
            "SERVER: $SERVER\r\n" +
            "ST: $target\r\n" +
            "USN: ${usn(target)}\r\n" +
            "Content-Length: 0\r\n\r\n"
        send(msg, address, port)
    }

    private fun announceLoop() {
        var round = 0
        while (running) {
            try {
                for (t in targets) sendNotify(t, "ssdp:alive")
            } catch (e: Exception) {
                Log.d(TAG, "notify failed: ${e.message}")
            }
            // Burst at start-up, then every 30 seconds.
            val delay = if (round++ < 3) 1_000L else 30_000L
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sendNotify(target: String, nts: String) {
        val loc = location() ?: return
        val msg = StringBuilder("NOTIFY * HTTP/1.1\r\n")
            .append("HOST: 239.255.255.250:1900\r\n")
            .append("CACHE-CONTROL: max-age=1800\r\n")
        if (nts == "ssdp:alive") {
            msg.append("LOCATION: ").append(loc).append("\r\n")
            msg.append("SERVER: ").append(SERVER).append("\r\n")
        }
        msg.append("NT: ").append(target).append("\r\n")
            .append("NTS: ").append(nts).append("\r\n")
            .append("USN: ").append(usn(target)).append("\r\n")
            .append("Content-Length: 0\r\n\r\n")
        send(msg.toString(), group, 1900)
    }

    private fun send(msg: String, address: InetAddress, port: Int) {
        val data = msg.toByteArray(Charsets.UTF_8)
        try {
            socket?.send(DatagramPacket(data, data.size, address, port))
        } catch (e: Exception) {
            Log.d(TAG, "send failed: ${e.message}")
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in text.split("\r\n", "\n").drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) map[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return map
    }

    companion object {
        private const val TAG = "Ssdp"
        const val SERVER = "Android/1.0 UPnP/1.0 LumaBeam/1.0"

        fun httpDate(): String {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT")
            return f.format(Date())
        }
    }
}
