package com.lumabeam.receiver.dlna

import android.util.Xml
import com.lumabeam.receiver.MediaKind
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** SOAP and DIDL-Lite helpers. */
object Soap {
    class Call(val action: String, val args: Map<String, String>)

    /** Parses a SOAP request body: returns the action element's name and its child values. */
    fun parse(body: String): Call? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(body))
        var inBody = false
        var action: String? = null
        var depth = 0
        var argName: String? = null
        val text = StringBuilder()
        val args = LinkedHashMap<String, String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val name = localName(parser.name)
                    when {
                        !inBody && name == "Body" -> inBody = true
                        inBody && action == null -> {
                            action = name
                            depth = parser.depth
                        }
                        action != null && parser.depth == depth + 1 -> {
                            argName = name
                            text.setLength(0)
                        }
                        // Some senders put DIDL-Lite in unescaped; rebuild it as text.
                        argName != null -> {
                            text.append('<').append(parser.name)
                            for (i in 0 until parser.attributeCount) {
                                text.append(' ').append(parser.getAttributeName(i)).append("=\"")
                                    .append(escape(parser.getAttributeValue(i))).append('"')
                            }
                            text.append('>')
                        }
                    }
                }
                XmlPullParser.TEXT -> if (argName != null) {
                    text.append(if (parser.depth > depth + 1) escape(parser.text) else parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (argName != null && parser.depth > depth + 1) {
                        text.append("</").append(parser.name).append('>')
                    } else if (argName != null && parser.depth == depth + 1) {
                        args[argName] = text.toString()
                        argName = null
                    } else if (action != null && parser.depth == depth) {
                        return Call(action, args)
                    }
                }
                XmlPullParser.END_DOCUMENT -> return action?.let { Call(it, args) }
            }
        }
    }

    private fun localName(n: String) = n.substringAfter(':')

    fun response(serviceType: String, action: String, values: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        sb.append("<s:Body><u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
        for ((k, v) in values) sb.append('<').append(k).append('>').append(escape(v)).append("</").append(k).append('>')
        sb.append("</u:").append(action).append("Response></s:Body></s:Envelope>")
        return sb.toString()
    }

    fun fault(code: Int, description: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>${escape(description)}</errorDescription></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** "H:MM:SS" as used by AVTransport. */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    /** Parses "H:MM:SS", "MM:SS", "SS" with optional fraction (".500"), returning ms. */
    fun parseTime(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            seconds = seconds * 60 + v
        }
        return (seconds * 1000).toLong()
    }

    class Didl(val title: String?, val kind: MediaKind?)

    /** Pulls dc:title and the media kind (from upnp:class or res@protocolInfo) out of DIDL-Lite metadata. */
    fun parseDidl(metadata: String): Didl {
        if (metadata.isBlank()) return Didl(null, null)
        var title: String? = null
        var upnpClass: String? = null
        var protocol: String? = null
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(metadata))
            var current: String? = null
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        current = localName(parser.name)
                        if (current == "res" && protocol == null) protocol = parser.getAttributeValue(null, "protocolInfo")
                    }
                    XmlPullParser.TEXT -> when (current) {
                        "title" -> if (title == null) title = parser.text.trim().ifEmpty { null }
                        "class" -> if (upnpClass == null) upnpClass = parser.text.trim()
                    }
                    XmlPullParser.END_TAG -> current = null
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (_: Exception) {
            // Malformed metadata is common; fall back to whatever was parsed.
        }
        val kind = when {
            upnpClass?.contains("videoItem") == true -> MediaKind.VIDEO
            upnpClass?.contains("audioItem") == true -> MediaKind.AUDIO
            upnpClass?.contains("imageItem") == true -> MediaKind.IMAGE
            else -> protocol?.let { kindFromMime(it.split(':').getOrNull(2).orEmpty()) }
        }
        return Didl(title, kind)
    }

    fun kindFromMime(mime: String): MediaKind? = when {
        mime.startsWith("video/") || mime.contains("mpegurl", ignoreCase = true) -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> null
    }

    fun kindFromUrl(url: String): MediaKind {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when (ext) {
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma" -> MediaKind.AUDIO
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> MediaKind.IMAGE
            else -> MediaKind.VIDEO
        }
    }
}
