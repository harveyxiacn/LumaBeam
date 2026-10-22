package com.lumabeam.core

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Wire format of the LumaBeam mirroring link (TCP, big-endian).
 *
 * Handshake, each direction: "LMB1" magic, u32 length, UTF-8 JSON.
 *   sender  -> receiver: {"name", "version"}
 *   receiver -> sender:  {"name", "width", "height"}  (receiver screen size in pixels)
 *
 * Then a stream of packets: u8 type, u8 flags, u32 length, i64 ptsUs, payload.
 */
object MirrorProtocol {
    private const val MAGIC = 0x4C4D4231 // "LMB1"
    const val VERSION = 1
    const val MAX_PACKET = 8 * 1024 * 1024

    /** Payload: u32 width, u32 height, then H.264 SPS/PPS (Annex-B). */
    const val VIDEO_CONFIG: Byte = 1
    /** Payload: one H.264 access unit (Annex-B). flags bit 0 = key frame. */
    const val VIDEO_FRAME: Byte = 2
    /** Payload: u32 sampleRate, u8 channelCount. Audio is 16-bit little-endian PCM. */
    const val AUDIO_CONFIG: Byte = 3
    const val AUDIO_FRAME: Byte = 4
    /** Keep-alive; either direction. */
    const val PING: Byte = 8
    /** Receiver -> sender: please send a key frame. */
    const val REQUEST_KEYFRAME: Byte = 9
    const val BYE: Byte = 10

    const val FLAG_KEY_FRAME = 1

    fun writeHello(out: DataOutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        out.writeInt(MAGIC)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun readHello(input: DataInputStream): JSONObject {
        if (input.readInt() != MAGIC) throw IOException("not a LumaBeam peer")
        val len = input.readInt()
        if (len !in 0..65536) throw IOException("bad hello length")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return JSONObject(String(bytes))
    }

    /** Callers must serialise writes to [out]. */
    fun writePacket(out: DataOutputStream, type: Byte, flags: Int, ptsUs: Long, data: ByteArray, off: Int = 0, len: Int = data.size) {
        out.writeByte(type.toInt())
        out.writeByte(flags)
        out.writeInt(len)
        out.writeLong(ptsUs)
        out.write(data, off, len)
    }

    class Packet(val type: Byte, val flags: Int, val ptsUs: Long, val data: ByteArray)

    fun readPacket(input: DataInputStream): Packet {
        val type = input.readByte()
        val flags = input.readUnsignedByte()
        val len = input.readInt()
        if (len !in 0..MAX_PACKET) throw IOException("bad packet length $len")
        val pts = input.readLong()
        val data = ByteArray(len)
        input.readFully(data)
        return Packet(type, flags, pts, data)
    }
}
