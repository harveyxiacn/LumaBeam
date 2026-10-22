package com.lumabeam.core

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A small HTTP/1.1 server. One request per connection ("Connection: close"),
 * which keeps it simple and is accepted by browsers and UPnP control points.
 * Arbitrary methods are allowed so it can also serve GENA SUBSCRIBE/UNSUBSCRIBE.
 */
class HttpServer(private val port: Int, private val handler: (HttpRequest) -> HttpResponse) {

    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        val executor = Executors.newCachedThreadPool()
        pool = executor
        running = true
        Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                executor.execute { serve(client) }
            }
        }, "http-accept-$port").start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        pool?.shutdownNow()
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
                val request = parseRequest(input, s.inetAddress) ?: return
                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    Log.w(TAG, "handler failed for ${request.method} ${request.path}", e)
                    HttpResponse.text(500, "Internal error")
                }
                // Drain whatever the handler did not read so the client sees a clean close.
                request.body.skip(Long.MAX_VALUE)
                writeResponse(s.getOutputStream(), request, response)
            } catch (e: SocketException) {
                // Client went away.
            } catch (e: IOException) {
                Log.d(TAG, "connection error: ${e.message}")
            }
        }
    }

    private fun parseRequest(input: InputStream, remote: InetAddress): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = decode(if (q >= 0) target.substring(0, q) else target)
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        val body: InputStream = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                ChunkedInputStream(input)
            headers["content-length"] != null ->
                LimitedInputStream(input, headers["content-length"]!!.toLongOrNull() ?: 0L)
            else -> ByteArrayInputStream(ByteArray(0))
        }
        return HttpRequest(parts[0].uppercase(), path, query, headers, body, remote)
    }

    private fun writeResponse(out: OutputStream, request: HttpRequest, response: HttpResponse) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        val headers = LinkedHashMap(response.headers)
        headers.putIfAbsent("Content-Type", response.contentType)
        headers["Content-Length"] = response.length.toString()
        headers["Connection"] = "close"
        headers.putIfAbsent("Server", "Android/1.0 UPnP/1.0 LumaBeam/1.0")
        for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (request.method != "HEAD") {
            response.body?.use { it.copyTo(out, 64 * 1024) }
        } else {
            response.body?.close()
        }
        out.flush()
    }

    companion object {
        private const val TAG = "HttpServer"

        internal fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (c == '\n'.code) break
                if (c != '\r'.code) buf.write(c)
                if (buf.size() > 16 * 1024) throw IOException("header line too long")
            }
            return buf.toString("ISO-8859-1")
        }

        fun parseQuery(raw: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) result[decode(pair)] = "" else result[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
            }
            return result
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            s
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            412 -> "Precondition Failed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            507 -> "Insufficient Storage"
            else -> "Status"
        }
    }
}

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
    val body: InputStream,
    val remote: InetAddress,
) {
    fun bodyText(limit: Int = 1 shl 20): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("body too large")
        }
        return out.toString("UTF-8")
    }
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: InputStream?,
    val length: Long,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun bytes(status: Int, type: String, data: ByteArray, headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, type, ByteArrayInputStream(data), data.size.toLong(), headers)

        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") =
            bytes(status, type, text.toByteArray())

        fun json(text: String) = text(200, text, "application/json; charset=utf-8")

        fun xml(text: String, status: Int = 200) = text(status, text, "text/xml; charset=\"utf-8\"")

        fun empty(status: Int = 200, headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, "text/plain", null, 0, headers)
    }
}

/** Reads exactly [remaining] bytes from the wrapped stream, then reports EOF. */
private class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val c = source.read()
        if (c < 0) throw EOFException()
        remaining--
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException()
        remaining -= n
        return n
    }

    override fun skip(n: Long): Long {
        var skipped = 0L
        val buf = ByteArray(8192)
        while (skipped < n && remaining > 0) {
            val r = read(buf, 0, minOf(buf.size.toLong(), n - skipped).toInt())
            if (r < 0) break
            skipped += r
        }
        return skipped
    }

    override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()
}

/** Decodes an HTTP/1.1 chunked request body. */
private class ChunkedInputStream(private val source: InputStream) : InputStream() {
    private var chunkLeft = 0L
    private var done = false

    private fun nextChunk(): Boolean {
        if (done) return false
        val line = HttpServer.readLine(source) ?: throw EOFException()
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")
        if (size == 0L) {
            while (HttpServer.readLine(source)?.isNotEmpty() == true) { /* trailers */ }
            done = true
            return false
        }
        chunkLeft = size
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (chunkLeft == 0L) {
            if (!nextChunk()) return -1
        }
        val n = source.read(b, off, minOf(len.toLong(), chunkLeft).toInt())
        if (n < 0) throw EOFException()
        chunkLeft -= n
        if (chunkLeft == 0L) HttpServer.readLine(source) // CRLF after the chunk data
        return n
    }

    override fun skip(n: Long): Long {
        var skipped = 0L
        val buf = ByteArray(8192)
        while (skipped < n) {
            val r = read(buf, 0, minOf(buf.size.toLong(), n - skipped).toInt())
            if (r < 0) break
            skipped += r
        }
        return skipped
    }
}
