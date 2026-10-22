package com.lumabeam.receiver.dlna

import android.content.Context
import android.util.Log
import com.lumabeam.core.HttpRequest
import com.lumabeam.core.HttpResponse
import com.lumabeam.core.Net
import com.lumabeam.receiver.MediaItem
import com.lumabeam.receiver.ReceiverHub
import com.lumabeam.receiver.TransportState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * UPnP AV MediaRenderer: AVTransport, RenderingControl and ConnectionManager
 * over SOAP, plus GENA eventing (LastChange) for controllers that subscribe.
 */
class DlnaRenderer(private val context: Context) {
    private val uuid = Net.deviceUuid(context)
    private val ssdp = Ssdp(uuid, Net.HTTP_PORT)
    private val notifier = Executors.newSingleThreadExecutor()

    private class Subscription(val service: String, val callbacks: List<String>, @Volatile var expiresAt: Long) {
        var seq = 0L
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val stateListener = { notifyAvTransport() }

    fun start() {
        ReceiverHub.addStateListener(stateListener)
        ssdp.start()
    }

    fun stop() {
        ReceiverHub.removeStateListener(stateListener)
        ssdp.stop()
        notifier.shutdownNow()
    }

    /** Returns null if the path is not a DLNA path. */
    fun handle(req: HttpRequest): HttpResponse? {
        if (!req.path.startsWith("/dlna/")) return null
        val rest = req.path.removePrefix("/dlna/")
        return when {
            rest == "description.xml" -> HttpResponse.xml(Descriptions.device(uuid, Net.deviceName(context)))
            rest == "AVTransport.xml" -> HttpResponse.xml(Descriptions.avTransport)
            rest == "RenderingControl.xml" -> HttpResponse.xml(Descriptions.renderingControl)
            rest == "ConnectionManager.xml" -> HttpResponse.xml(Descriptions.connectionManager)
            rest.endsWith("/control") && req.method == "POST" -> control(rest.removeSuffix("/control"), req)
            rest.endsWith("/event") -> event(rest.removeSuffix("/event"), req)
            else -> HttpResponse.text(404, "Not found")
        }
    }

    // ---- SOAP control ----

    private class UpnpError(val code: Int, message: String) : Exception(message)

    private fun control(service: String, req: HttpRequest): HttpResponse {
        val serviceType = Descriptions.SERVICES[service] ?: return HttpResponse.text(404, "Unknown service")
        val call = try {
            Soap.parse(req.bodyText())
        } catch (e: Exception) {
            null
        } ?: return HttpResponse.xml(Soap.fault(402, "Invalid Args"), 500)
        Log.d(TAG, "$service.${call.action} ${call.args.keys}")
        return try {
            val values = when (service) {
                "AVTransport" -> avTransport(call)
                "RenderingControl" -> renderingControl(call)
                else -> connectionManager(call)
            }
            HttpResponse.xml(Soap.response(serviceType, call.action, values))
        } catch (e: UpnpError) {
            HttpResponse.xml(Soap.fault(e.code, e.message ?: "Error"), 500)
        }
    }

    private fun avTransport(call: Soap.Call): List<Pair<String, String>> {
        val item = ReceiverHub.current
        return when (call.action) {
            "SetAVTransportURI" -> {
                val uri = call.args["CurrentURI"].orEmpty().trim()
                if (uri.isEmpty()) throw UpnpError(716, "Resource not found")
                val meta = call.args["CurrentURIMetaData"].orEmpty()
                val didl = Soap.parseDidl(meta)
                val title = didl.title ?: titleFromUrl(uri)
                val kind = didl.kind ?: Soap.kindFromUrl(uri)
                // Most controllers follow with Play; start buffering right away for a faster start.
                ReceiverHub.load(MediaItem(uri, title, kind, meta, "DLNA"), autoPlay = false)
                emptyList()
            }
            "SetNextAVTransportURI" -> emptyList()
            "Play" -> {
                if (item == null) throw UpnpError(701, "Transition not available")
                ReceiverHub.play()
                emptyList()
            }
            "Pause" -> {
                ReceiverHub.pause()
                emptyList()
            }
            "Stop" -> {
                ReceiverHub.stop()
                emptyList()
            }
            "Seek" -> {
                val unit = call.args["Unit"].orEmpty()
                if (unit != "REL_TIME" && unit != "ABS_TIME") throw UpnpError(710, "Seek mode not supported")
                val ms = Soap.parseTime(call.args["Target"].orEmpty()) ?: throw UpnpError(711, "Illegal seek target")
                ReceiverHub.seek(ms)
                emptyList()
            }
            "Next", "Previous" -> emptyList()
            "GetTransportInfo" -> listOf(
                "CurrentTransportState" to ReceiverHub.state.name,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> listOf(
                "Track" to if (item == null) "0" else "1",
                "TrackDuration" to Soap.formatTime(ReceiverHub.durationMs),
                "TrackMetaData" to item?.metadata.orEmpty(),
                "TrackURI" to item?.uri.orEmpty(),
                "RelTime" to Soap.formatTime(ReceiverHub.positionMs),
                "AbsTime" to Soap.formatTime(ReceiverHub.positionMs),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647",
            )
            "GetMediaInfo" -> listOf(
                "NrTracks" to if (item == null) "0" else "1",
                "MediaDuration" to Soap.formatTime(ReceiverHub.durationMs),
                "CurrentURI" to item?.uri.orEmpty(),
                "CurrentURIMetaData" to item?.metadata.orEmpty(),
                "NextURI" to "",
                "NextURIMetaData" to "",
                "PlayMedium" to if (item == null) "NONE" else "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetDeviceCapabilities" -> listOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to currentActions())
            else -> throw UpnpError(401, "Invalid Action")
        }
    }

    private fun currentActions(): String = when (ReceiverHub.state) {
        TransportState.PLAYING -> "Pause,Stop,Seek"
        TransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek"
        TransportState.STOPPED -> "Play,Seek"
        TransportState.TRANSITIONING -> "Stop"
        TransportState.NO_MEDIA_PRESENT -> ""
    }

    private fun renderingControl(call: Soap.Call): List<Pair<String, String>> = when (call.action) {
        "GetVolume" -> listOf("CurrentVolume" to ReceiverHub.volumePercent().toString())
        "SetVolume" -> {
            val v = call.args["DesiredVolume"]?.trim()?.toIntOrNull() ?: throw UpnpError(402, "Invalid Args")
            ReceiverHub.setVolumePercent(v)
            notifyRendering()
            emptyList()
        }
        "GetMute" -> listOf("CurrentMute" to if (ReceiverHub.isMuted()) "1" else "0")
        "SetMute" -> {
            val m = call.args["DesiredMute"]?.trim()
            ReceiverHub.setMuted(m == "1" || m.equals("true", ignoreCase = true))
            notifyRendering()
            emptyList()
        }
        "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
        "SelectPreset" -> emptyList()
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun connectionManager(call: Soap.Call): List<Pair<String, String>> = when (call.action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to Descriptions.SINK_PROTOCOLS)
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "",
            "PeerConnectionManager" to "", "PeerConnectionID" to "-1",
            "Direction" to "Input", "Status" to "OK",
        )
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun titleFromUrl(uri: String): String {
        val name = uri.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return try {
            java.net.URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            name
        }.ifBlank { "Media" }
    }

    // ---- GENA eventing ----

    private fun event(service: String, req: HttpRequest): HttpResponse {
        if (service !in Descriptions.SERVICES) return HttpResponse.text(404, "Unknown service")
        val timeoutSec = req.headers["timeout"]?.substringAfter("Second-", "")?.toLongOrNull()?.coerceIn(60, 3600) ?: 1800
        when (req.method) {
            "SUBSCRIBE" -> {
                val existing = req.headers["sid"]
                if (existing != null) {
                    val sub = subscriptions[existing] ?: return HttpResponse.empty(412)
                    sub.expiresAt = System.currentTimeMillis() + timeoutSec * 1000
                    return HttpResponse.empty(200, mapOf("SID" to existing, "TIMEOUT" to "Second-$timeoutSec"))
                }
                val callbacks = Regex("<([^>]+)>").findAll(req.headers["callback"].orEmpty()).map { it.groupValues[1] }.toList()
                if (callbacks.isEmpty()) return HttpResponse.empty(412)
                val sid = "uuid:" + UUID.randomUUID()
                val sub = Subscription(service, callbacks, System.currentTimeMillis() + timeoutSec * 1000)
                subscriptions[sid] = sub
                // The initial event must arrive after the SUBSCRIBE response.
                notifier.execute {
                    Thread.sleep(200)
                    send(sid, sub, initialEvent(service))
                }
                return HttpResponse.empty(200, mapOf("SID" to sid, "TIMEOUT" to "Second-$timeoutSec"))
            }
            "UNSUBSCRIBE" -> {
                subscriptions.remove(req.headers["sid"])
                return HttpResponse.empty(200)
            }
        }
        return HttpResponse.empty(405)
    }

    private fun initialEvent(service: String): String = when (service) {
        "AVTransport" -> propertySet("LastChange", avLastChange())
        "RenderingControl" -> propertySet("LastChange", rcLastChange())
        else -> propertySet("SinkProtocolInfo", Descriptions.SINK_PROTOCOLS)
    }

    private fun avLastChange(): String {
        val item = ReceiverHub.current
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
            "<TransportState val=\"${ReceiverHub.state.name}\"/>" +
            "<TransportStatus val=\"OK\"/>" +
            "<CurrentTransportActions val=\"${currentActions()}\"/>" +
            "<AVTransportURI val=\"${Soap.escape(item?.uri.orEmpty())}\"/>" +
            "<CurrentTrackURI val=\"${Soap.escape(item?.uri.orEmpty())}\"/>" +
            "<CurrentMediaDuration val=\"${Soap.formatTime(ReceiverHub.durationMs)}\"/>" +
            "<CurrentTrackDuration val=\"${Soap.formatTime(ReceiverHub.durationMs)}\"/>" +
            "</InstanceID></Event>"
    }

    private fun rcLastChange(): String =
        "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"${ReceiverHub.volumePercent()}\"/>" +
            "<Mute channel=\"Master\" val=\"${if (ReceiverHub.isMuted()) 1 else 0}\"/>" +
            "</InstanceID></Event>"

    private fun propertySet(name: String, value: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><$name>" +
            Soap.escape(value) + "</$name></e:property></e:propertyset>"

    private fun notifyAvTransport() = broadcast("AVTransport") { propertySet("LastChange", avLastChange()) }

    private fun notifyRendering() = broadcast("RenderingControl") { propertySet("LastChange", rcLastChange()) }

    private fun broadcast(service: String, body: () -> String) {
        val now = System.currentTimeMillis()
        subscriptions.entries.removeIf { it.value.expiresAt < now }
        val targets = subscriptions.entries.filter { it.value.service == service }
        if (targets.isEmpty()) return
        val payload = body()
        for ((sid, sub) in targets) {
            try {
                notifier.execute { send(sid, sub, payload) }
            } catch (_: Exception) {
                // Executor shut down.
            }
        }
    }

    private fun send(sid: String, sub: Subscription, body: String) {
        val bytes = body.toByteArray()
        val seq = synchronized(sub) { sub.seq++ }
        for (callback in sub.callbacks) {
            try {
                val uri = URI(callback)
                val port = if (uri.port > 0) uri.port else 80
                Socket().use { s ->
                    s.connect(InetSocketAddress(uri.host, port), 3000)
                    s.soTimeout = 3000
                    val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                    val head = "NOTIFY $path HTTP/1.1\r\n" +
                        "HOST: ${uri.host}:$port\r\n" +
                        "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                        "NT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                        "SID: $sid\r\nSEQ: $seq\r\n" +
                        "CONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n"
                    val out = s.getOutputStream()
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(bytes)
                    out.flush()
                    s.getInputStream().read(ByteArray(256)) // wait for the controller's reply
                }
                return
            } catch (e: IOException) {
                Log.d(TAG, "event to $callback failed: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "bad callback $callback")
            }
        }
    }

    companion object {
        private const val TAG = "DlnaRenderer"
    }
}
