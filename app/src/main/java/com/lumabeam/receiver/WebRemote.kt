package com.lumabeam.receiver

import android.content.Context
import android.util.Log
import com.lumabeam.core.HttpRequest
import com.lumabeam.core.HttpResponse
import com.lumabeam.core.Net
import com.lumabeam.receiver.dlna.Soap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Browser casting for any phone, iPhone or iPad: serves a single page that
 * uploads a photo, video or song to the TV (or sends a link), plus a remote.
 */
class WebRemote(private val context: Context) {
    private val uploadDir = File(context.cacheDir, "uploads")
    private val uploadLock = Object()

    fun handle(req: HttpRequest): HttpResponse? = when (req.path) {
        "/", "/index.html" -> page()
        "/api/status" -> status()
        "/api/upload" -> if (req.method == "PUT" || req.method == "POST") upload(req) else HttpResponse.empty(405)
        "/api/url" -> if (req.method == "POST") playUrl(req) else HttpResponse.empty(405)
        "/api/control" -> control(req)
        else -> null
    }

    private fun page(): HttpResponse {
        val html = context.assets.open("web/index.html").use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .replace("{{NAME}}", Soap.escape(Net.deviceName(context)))
        return HttpResponse.bytes(200, "text/html; charset=utf-8", html.toByteArray(), mapOf("Cache-Control" to "no-store"))
    }

    private fun status(): HttpResponse {
        val item = ReceiverHub.current
        val json = JSONObject()
            .put("name", Net.deviceName(context))
            .put("state", ReceiverHub.state.name)
            .put("title", item?.title ?: "")
            .put("kind", item?.kind?.name ?: "")
            .put("position", ReceiverHub.positionMs)
            .put("duration", ReceiverHub.durationMs)
            .put("volume", ReceiverHub.volumePercent())
            .put("mirroring", ReceiverHub.mirror?.senderName ?: "")
        return HttpResponse.json(json.toString())
    }

    private fun upload(req: HttpRequest): HttpResponse {
        val rawName = req.query["name"].orEmpty().ifBlank { "media" }
        val name = rawName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120)
        val type = req.query["type"].orEmpty().ifEmpty { req.headers["content-type"].orEmpty() }
        val kind = Soap.kindFromMime(type) ?: Soap.kindFromUrl(name)
        val length = req.headers["content-length"]?.toLongOrNull()

        synchronized(uploadLock) {
            uploadDir.mkdirs()
            // Keep only the file that is currently on screen; everything else is stale.
            val playing = ReceiverHub.current?.uri
            uploadDir.listFiles()?.forEach { if ("file://${it.absolutePath}" != playing) it.delete() }
            if (length != null && length > uploadDir.usableSpace - 50L * 1024 * 1024) {
                return HttpResponse.text(507, "Not enough free space on the TV")
            }
            val target = File(uploadDir, "${System.currentTimeMillis()}-$name")
            try {
                FileOutputStream(target).use { out -> req.body.copyTo(out, 256 * 1024) }
            } catch (e: Exception) {
                Log.w(TAG, "upload failed: ${e.message}")
                target.delete()
                return HttpResponse.text(400, "Upload interrupted")
            }
            val title = rawName.substringBeforeLast('.').ifBlank { rawName }
            ReceiverHub.load(MediaItem("file://${target.absolutePath}", title, kind, source = "Web"), autoPlay = true)
        }
        return HttpResponse.json("{\"ok\":true}")
    }

    private fun playUrl(req: HttpRequest): HttpResponse {
        val body = try {
            JSONObject(req.bodyText(64 * 1024))
        } catch (e: Exception) {
            return HttpResponse.text(400, "Expected JSON")
        }
        val url = body.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("rtsp://")) {
            return HttpResponse.text(400, "Only http(s) and rtsp links are supported")
        }
        val title = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
        ReceiverHub.load(MediaItem(url, title, Soap.kindFromUrl(url), source = "Web"), autoPlay = true)
        return HttpResponse.json("{\"ok\":true}")
    }

    private fun control(req: HttpRequest): HttpResponse {
        when (req.query["cmd"]) {
            "play" -> ReceiverHub.play()
            "pause" -> ReceiverHub.pause()
            "stop" -> ReceiverHub.stop()
            "seek" -> req.query["ms"]?.toLongOrNull()?.let { ReceiverHub.seek(it) }
            "volume" -> req.query["v"]?.toIntOrNull()?.let { ReceiverHub.setVolumePercent(it) }
            "stopMirror" -> ReceiverHub.mirror?.close()
            else -> return HttpResponse.text(400, "Unknown command")
        }
        return HttpResponse.json("{\"ok\":true}")
    }

    companion object {
        private const val TAG = "WebRemote"
    }
}
