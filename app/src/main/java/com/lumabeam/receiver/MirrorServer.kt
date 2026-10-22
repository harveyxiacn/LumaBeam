package com.lumabeam.receiver

import android.content.Context
import android.util.Log
import com.lumabeam.core.MirrorProtocol
import com.lumabeam.core.Net
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Accepts mirroring senders. A new sender replaces the current one. */
class MirrorServer(private val context: Context) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(Net.MIRROR_PORT))
        server = s
        running = true
        Thread({
            while (running) {
                val client = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                Thread({ handshake(client) }, "mirror-handshake").start()
            }
        }, "mirror-accept").start()
    }

    private fun handshake(client: java.net.Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 10_000
            client.receiveBufferSize = 1 shl 20
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 256 * 1024))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            val hello = MirrorProtocol.readHello(input)
            val metrics = context.resources.displayMetrics
            val width = maxOf(metrics.widthPixels, metrics.heightPixels)
            val height = minOf(metrics.widthPixels, metrics.heightPixels)
            MirrorProtocol.writeHello(
                output,
                JSONObject()
                    .put("name", Net.deviceName(context))
                    .put("version", MirrorProtocol.VERSION)
                    .put("width", width)
                    .put("height", height),
            )
            val name = hello.optString("name", client.inetAddress.hostAddress ?: "phone")
            Log.i(TAG, "mirroring from $name (${client.inetAddress.hostAddress})")
            val session = MirrorSession(client, input, output, name)
            ReceiverHub.mirrorStarted(session)
            session.start()
        } catch (e: Exception) {
            Log.w(TAG, "handshake failed: ${e.message}")
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        ReceiverHub.mirror?.close()
    }

    companion object {
        private const val TAG = "MirrorServer"
    }
}
