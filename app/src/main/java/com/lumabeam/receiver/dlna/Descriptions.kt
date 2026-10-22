package com.lumabeam.receiver.dlna

/** UPnP device and service description documents for the MediaRenderer. */
object Descriptions {
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"

    val SERVICES = mapOf("AVTransport" to AVT, "RenderingControl" to RCS, "ConnectionManager" to CMS)

    /** Formats this renderer can play (MediaPlayer decodes all of these on typical TVs). */
    val SINK_PROTOCOLS = listOf(
        "video/mp4", "video/x-matroska", "video/webm", "video/mpeg", "video/quicktime", "video/x-msvideo",
        "video/3gpp", "video/mp2t", "video/MP2T", "application/vnd.apple.mpegurl", "application/x-mpegURL",
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-flac", "audio/flac", "audio/wav", "audio/x-wav",
        "audio/ogg", "image/jpeg", "image/png", "image/gif", "image/webp",
    ).joinToString(",") { "http-get:*:$it:*" } + ",http-get:*:*:*"

    fun device(uuid: String, name: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>${Soap.escape(name)}</friendlyName>
    <manufacturer>LumaBeam</manufacturer>
    <modelDescription>Android TV screen and media receiver</modelDescription>
    <modelName>LumaBeam</modelName>
    <modelNumber>1.0</modelNumber>
    <serialNumber>${uuid.take(8)}</serialNumber>
    <UDN>uuid:$uuid</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
${SERVICES.entries.joinToString("\n") { (id, type) -> service(id, type) }}
    </serviceList>
  </device>
</root>
"""

    private fun service(id: String, type: String) = """      <service>
        <serviceType>$type</serviceType>
        <serviceId>urn:upnp-org:serviceId:$id</serviceId>
        <SCPDURL>/dlna/$id.xml</SCPDURL>
        <controlURL>/dlna/$id/control</controlURL>
        <eventSubURL>/dlna/$id/event</eventSubURL>
      </service>"""

    // ---- Service control protocol descriptions ----

    private class Arg(val name: String, val out: Boolean, val variable: String)
    private class Action(val name: String, val args: List<Arg>)
    private class Variable(val name: String, val type: String, val events: Boolean = false, val allowed: List<String> = emptyList())

    private fun inArg(name: String, variable: String) = Arg(name, false, variable)
    private fun outArg(name: String, variable: String) = Arg(name, true, variable)
    private val instance = inArg("InstanceID", "A_ARG_TYPE_InstanceID")

    private fun scpd(actions: List<Action>, variables: List<Variable>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n")
        sb.append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n  <actionList>\n")
        for (a in actions) {
            sb.append("    <action><name>").append(a.name).append("</name><argumentList>\n")
            for (arg in a.args) {
                sb.append("      <argument><name>").append(arg.name).append("</name><direction>")
                    .append(if (arg.out) "out" else "in").append("</direction><relatedStateVariable>")
                    .append(arg.variable).append("</relatedStateVariable></argument>\n")
            }
            sb.append("    </argumentList></action>\n")
        }
        sb.append("  </actionList>\n  <serviceStateTable>\n")
        for (v in variables) {
            sb.append("    <stateVariable sendEvents=\"").append(if (v.events) "yes" else "no").append("\"><name>")
                .append(v.name).append("</name><dataType>").append(v.type).append("</dataType>")
            if (v.allowed.isNotEmpty()) {
                sb.append("<allowedValueList>")
                v.allowed.forEach { sb.append("<allowedValue>").append(it).append("</allowedValue>") }
                sb.append("</allowedValueList>")
            }
            if (v.name == "Volume") {
                sb.append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>")
            }
            sb.append("</stateVariable>\n")
        }
        sb.append("  </serviceStateTable>\n</scpd>\n")
        return sb.toString()
    }

    val avTransport: String by lazy {
        scpd(
            listOf(
                Action("SetAVTransportURI", listOf(instance, inArg("CurrentURI", "AVTransportURI"), inArg("CurrentURIMetaData", "AVTransportURIMetaData"))),
                Action("SetNextAVTransportURI", listOf(instance, inArg("NextURI", "NextAVTransportURI"), inArg("NextURIMetaData", "NextAVTransportURIMetaData"))),
                Action(
                    "GetMediaInfo",
                    listOf(
                        instance, outArg("NrTracks", "NumberOfTracks"), outArg("MediaDuration", "CurrentMediaDuration"),
                        outArg("CurrentURI", "AVTransportURI"), outArg("CurrentURIMetaData", "AVTransportURIMetaData"),
                        outArg("NextURI", "NextAVTransportURI"), outArg("NextURIMetaData", "NextAVTransportURIMetaData"),
                        outArg("PlayMedium", "PlaybackStorageMedium"), outArg("RecordMedium", "RecordStorageMedium"),
                        outArg("WriteStatus", "RecordMediumWriteStatus"),
                    ),
                ),
                Action(
                    "GetTransportInfo",
                    listOf(instance, outArg("CurrentTransportState", "TransportState"), outArg("CurrentTransportStatus", "TransportStatus"), outArg("CurrentSpeed", "TransportPlaySpeed")),
                ),
                Action(
                    "GetPositionInfo",
                    listOf(
                        instance, outArg("Track", "CurrentTrack"), outArg("TrackDuration", "CurrentTrackDuration"),
                        outArg("TrackMetaData", "CurrentTrackMetaData"), outArg("TrackURI", "CurrentTrackURI"),
                        outArg("RelTime", "RelativeTimePosition"), outArg("AbsTime", "AbsoluteTimePosition"),
                        outArg("RelCount", "RelativeCounterPosition"), outArg("AbsCount", "AbsoluteCounterPosition"),
                    ),
                ),
                Action(
                    "GetDeviceCapabilities",
                    listOf(instance, outArg("PlayMedia", "PossiblePlaybackStorageMedia"), outArg("RecMedia", "PossibleRecordStorageMedia"), outArg("RecQualityModes", "PossibleRecordQualityModes")),
                ),
                Action("GetTransportSettings", listOf(instance, outArg("PlayMode", "CurrentPlayMode"), outArg("RecQualityMode", "CurrentRecordQualityMode"))),
                Action("Stop", listOf(instance)),
                Action("Play", listOf(instance, inArg("Speed", "TransportPlaySpeed"))),
                Action("Pause", listOf(instance)),
                Action("Seek", listOf(instance, inArg("Unit", "A_ARG_TYPE_SeekMode"), inArg("Target", "A_ARG_TYPE_SeekTarget"))),
                Action("Next", listOf(instance)),
                Action("Previous", listOf(instance)),
                Action("GetCurrentTransportActions", listOf(instance, outArg("Actions", "CurrentTransportActions"))),
            ),
            listOf(
                Variable("TransportState", "string", allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")),
                Variable("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
                Variable("PlaybackStorageMedium", "string", allowed = listOf("NONE", "NETWORK")),
                Variable("RecordStorageMedium", "string", allowed = listOf("NOT_IMPLEMENTED")),
                Variable("PossiblePlaybackStorageMedia", "string"),
                Variable("PossibleRecordStorageMedia", "string"),
                Variable("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
                Variable("TransportPlaySpeed", "string", allowed = listOf("1")),
                Variable("RecordMediumWriteStatus", "string", allowed = listOf("NOT_IMPLEMENTED")),
                Variable("CurrentRecordQualityMode", "string", allowed = listOf("NOT_IMPLEMENTED")),
                Variable("PossibleRecordQualityModes", "string"),
                Variable("NumberOfTracks", "ui4"),
                Variable("CurrentTrack", "ui4"),
                Variable("CurrentTrackDuration", "string"),
                Variable("CurrentMediaDuration", "string"),
                Variable("CurrentTrackMetaData", "string"),
                Variable("CurrentTrackURI", "string"),
                Variable("AVTransportURI", "string"),
                Variable("AVTransportURIMetaData", "string"),
                Variable("NextAVTransportURI", "string"),
                Variable("NextAVTransportURIMetaData", "string"),
                Variable("RelativeTimePosition", "string"),
                Variable("AbsoluteTimePosition", "string"),
                Variable("RelativeCounterPosition", "i4"),
                Variable("AbsoluteCounterPosition", "i4"),
                Variable("CurrentTransportActions", "string"),
                Variable("LastChange", "string", events = true),
                Variable("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
                Variable("A_ARG_TYPE_SeekTarget", "string"),
                Variable("A_ARG_TYPE_InstanceID", "ui4"),
            ),
        )
    }

    val renderingControl: String by lazy {
        val channel = inArg("Channel", "A_ARG_TYPE_Channel")
        scpd(
            listOf(
                Action("ListPresets", listOf(instance, outArg("CurrentPresetNameList", "PresetNameList"))),
                Action("SelectPreset", listOf(instance, inArg("PresetName", "A_ARG_TYPE_PresetName"))),
                Action("GetMute", listOf(instance, channel, outArg("CurrentMute", "Mute"))),
                Action("SetMute", listOf(instance, channel, inArg("DesiredMute", "Mute"))),
                Action("GetVolume", listOf(instance, channel, outArg("CurrentVolume", "Volume"))),
                Action("SetVolume", listOf(instance, channel, inArg("DesiredVolume", "Volume"))),
            ),
            listOf(
                Variable("PresetNameList", "string"),
                Variable("LastChange", "string", events = true),
                Variable("Mute", "boolean"),
                Variable("Volume", "ui2"),
                Variable("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
                Variable("A_ARG_TYPE_InstanceID", "ui4"),
                Variable("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")),
            ),
        )
    }

    val connectionManager: String by lazy {
        scpd(
            listOf(
                Action("GetProtocolInfo", listOf(outArg("Source", "SourceProtocolInfo"), outArg("Sink", "SinkProtocolInfo"))),
                Action("GetCurrentConnectionIDs", listOf(outArg("ConnectionIDs", "CurrentConnectionIDs"))),
                Action(
                    "GetCurrentConnectionInfo",
                    listOf(
                        inArg("ConnectionID", "A_ARG_TYPE_ConnectionID"), outArg("RcsID", "A_ARG_TYPE_RcsID"),
                        outArg("AVTransportID", "A_ARG_TYPE_AVTransportID"), outArg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                        outArg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), outArg("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
                        outArg("Direction", "A_ARG_TYPE_Direction"), outArg("Status", "A_ARG_TYPE_ConnectionStatus"),
                    ),
                ),
            ),
            listOf(
                Variable("SourceProtocolInfo", "string", events = true),
                Variable("SinkProtocolInfo", "string", events = true),
                Variable("CurrentConnectionIDs", "string", events = true),
                Variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
                Variable("A_ARG_TYPE_ConnectionManager", "string"),
                Variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
                Variable("A_ARG_TYPE_ProtocolInfo", "string"),
                Variable("A_ARG_TYPE_ConnectionID", "i4"),
                Variable("A_ARG_TYPE_AVTransportID", "i4"),
                Variable("A_ARG_TYPE_RcsID", "i4"),
            ),
        )
    }
}
