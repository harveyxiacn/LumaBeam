package com.lumabeam.sender

import com.lumabeam.core.MirrorProtocol
import java.io.DataOutputStream
import java.util.ArrayDeque

/**
 * Outgoing packet queue with congestion control. Video is the only thing that
 * can overwhelm Wi-Fi, so when video backs up we drop it (never audio or
 * codec config), skip to the next key frame and tell the encoder to lower its
 * bitrate. This trades a brief quality dip for staying live, instead of the
 * growing delay that makes other cast apps feel laggy.
 */
class SendQueue(private val callbacks: Callbacks) {
    interface Callbacks {
        /** Video backed up: the encoder should emit a key frame and (rate-limited) lower its bitrate. Called under the queue lock. */
        fun onCongestion()
        /** Link has been healthy for a while: the encoder may raise its bitrate. */
        fun onHealthy()
    }

    private class Item(val type: Byte, val flags: Int, val pts: Long, val data: ByteArray)

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private var videoBytes = 0L
    private var audioBytes = 0L
    private var waitForKey = false
    private var lastCongestion = System.nanoTime()
    private var lastHealthy = System.nanoTime()
    @Volatile private var closed = false

    /** Current target video bitrate, used to convert queued bytes into time. */
    @Volatile var videoBitrate = 8_000_000
    var audioBytesPerSecond = 48_000 * 4

    @Volatile var sentBytes = 0L
        private set

    fun offerConfig(type: Byte, data: ByteArray) = synchronized(lock) {
        queue.addLast(Item(type, 0, 0, data))
        lock.notifyAll()
    }

    fun offerVideo(data: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        if (closed) return
        if (waitForKey) {
            if (!key) return
            waitForKey = false
        }
        val maxBytes = videoBitrate / 8 * MAX_VIDEO_DELAY_MS / 1000
        if (videoBytes + data.size > maxBytes && !key) {
            // Congested: discard queued video and resume at the next key frame.
            dropQueuedVideo()
            waitForKey = true
            lastCongestion = System.nanoTime()
            callbacks.onCongestion()
            return
        }
        queue.addLast(Item(MirrorProtocol.VIDEO_FRAME, if (key) MirrorProtocol.FLAG_KEY_FRAME else 0, ptsUs, data))
        videoBytes += data.size
        lock.notifyAll()
    }

    fun offerAudio(data: ByteArray, ptsUs: Long) = synchronized(lock) {
        if (closed) return
        val maxBytes = audioBytesPerSecond.toLong() * MAX_AUDIO_DELAY_MS / 1000
        if (audioBytes + data.size > maxBytes) {
            val it = queue.iterator()
            while (it.hasNext() && audioBytes + data.size > maxBytes) {
                val item = it.next()
                if (item.type == MirrorProtocol.AUDIO_FRAME) {
                    it.remove()
                    audioBytes -= item.data.size
                }
            }
        }
        queue.addLast(Item(MirrorProtocol.AUDIO_FRAME, 0, ptsUs, data))
        audioBytes += data.size
        lock.notifyAll()
    }

    fun offerControl(type: Byte) = synchronized(lock) {
        queue.addLast(Item(type, 0, 0, ByteArray(0)))
        lock.notifyAll()
    }

    private fun dropQueuedVideo() {
        val it = queue.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (item.type == MirrorProtocol.VIDEO_FRAME) {
                it.remove()
                videoBytes -= item.data.size
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        queue.clear()
        lock.notifyAll()
    }

    /**
     * Writes packets until [close] or an IO error (which is thrown).
     * Sends a keep-alive when idle.
     */
    fun writeLoop(out: DataOutputStream) {
        while (true) {
            val item = synchronized(lock) {
                while (queue.isEmpty() && !closed) {
                    lock.wait(KEEPALIVE_MS)
                    if (queue.isEmpty() && !closed) queue.addLast(Item(MirrorProtocol.PING, 0, 0, ByteArray(0)))
                }
                if (closed) return
                val next = queue.removeFirst()
                when (next.type) {
                    MirrorProtocol.VIDEO_FRAME -> videoBytes -= next.data.size
                    MirrorProtocol.AUDIO_FRAME -> audioBytes -= next.data.size
                }
                val now = System.nanoTime()
                if (videoBytes == 0L && now - lastCongestion > 3_000_000_000L && now - lastHealthy > 2_000_000_000L) {
                    lastHealthy = now
                    callbacks.onHealthy()
                }
                next
            }
            MirrorProtocol.writePacket(out, item.type, item.flags, item.pts, item.data)
            if (synchronized(lock) { queue.isEmpty() }) out.flush()
            sentBytes += item.data.size + 14
        }
    }

    companion object {
        /** Maximum video kept waiting to be sent before we start dropping. */
        const val MAX_VIDEO_DELAY_MS = 120
        const val MAX_AUDIO_DELAY_MS = 200
        const val KEEPALIVE_MS = 2000L
    }
}
