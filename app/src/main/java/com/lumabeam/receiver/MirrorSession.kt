package com.lumabeam.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.lumabeam.core.MirrorProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One connected mirroring sender. Reads packets on its own thread, feeds H.264
 * into a hardware decoder rendering straight to the activity's Surface, and
 * plays PCM audio through an AudioTrack. Everything is tuned for low latency:
 * late data is dropped rather than queued.
 */
class MirrorSession(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val senderName: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    @Volatile private var closed = false

    // Video state, guarded by [lock].
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var configData: ByteArray? = null
    @Volatile var videoWidth = 0
        private set
    @Volatile var videoHeight = 0
        private set
    private var waitingForKeyFrame = true

    /** Called on the main thread when the video size changes. */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    // Audio.
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private val audioQueue = ArrayBlockingQueue<ByteArray>(64)
    private var audioBytesPerSecond = 48000 * 4

    fun start() {
        Thread(::readLoop, "mirror-read").start()
    }

    // ---- Surface (main thread) ----

    fun attachSurface(s: Surface) {
        synchronized(lock) {
            surface = s
            rebuildDecoderLocked()
        }
        requestKeyFrame()
    }

    fun detachSurface() {
        synchronized(lock) {
            surface = null
            releaseDecoderLocked()
        }
    }

    // ---- Network ----

    private fun readLoop() {
        try {
            socket.soTimeout = 15_000
            while (!closed) {
                val p = MirrorProtocol.readPacket(input)
                when (p.type) {
                    MirrorProtocol.VIDEO_CONFIG -> onVideoConfig(p.data)
                    MirrorProtocol.VIDEO_FRAME -> onVideoFrame(p.data, p.ptsUs, p.flags and MirrorProtocol.FLAG_KEY_FRAME != 0)
                    MirrorProtocol.AUDIO_CONFIG -> onAudioConfig(p.data)
                    MirrorProtocol.AUDIO_FRAME -> onAudioFrame(p.data)
                    MirrorProtocol.PING -> send(MirrorProtocol.PING)
                    MirrorProtocol.BYE -> break
                }
            }
        } catch (e: IOException) {
            if (!closed) Log.i(TAG, "sender disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "mirror session failed", e)
        } finally {
            close()
        }
    }

    private fun send(type: Byte) {
        try {
            synchronized(output) {
                MirrorProtocol.writePacket(output, type, 0, 0, ByteArray(0))
                output.flush()
            }
        } catch (_: IOException) {
        }
    }

    @Volatile private var lastKeyRequest = 0L

    fun requestKeyFrame() {
        val now = System.currentTimeMillis()
        if (closed || now - lastKeyRequest < 300) return
        lastKeyRequest = now
        Thread { send(MirrorProtocol.REQUEST_KEYFRAME) }.start()
    }

    fun close() {
        if (closed) return
        closed = true
        Thread {
            send(MirrorProtocol.BYE)
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }.start()
        synchronized(lock) { releaseDecoderLocked() }
        audioThread?.interrupt()
        ReceiverHub.mirrorEnded(this)
    }

    val isClosed get() = closed

    // ---- Video ----

    private fun onVideoConfig(data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        val w = buf.int
        val h = buf.int
        val csd = data.copyOfRange(8, data.size)
        synchronized(lock) {
            videoWidth = w
            videoHeight = h
            configData = csd
            rebuildDecoderLocked()
        }
        main.post { onVideoSize?.invoke(w, h) }
    }

    private fun rebuildDecoderLocked() {
        releaseDecoderLocked()
        val s = surface ?: return
        val csd = configData ?: return
        if (!s.isValid) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            val (sps, pps) = splitParameterSets(csd)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight, 1 shl 20))
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= 23) format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, s, null, 0)
            codec.start()
            decoder = codec
            waitingForKeyFrame = true
            Thread({ drainLoop(codec) }, "mirror-render").start()
        } catch (e: Exception) {
            Log.e(TAG, "cannot start video decoder", e)
            decoder = null
        }
    }

    private fun releaseDecoderLocked() {
        val codec = decoder ?: return
        decoder = null
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }

    private fun onVideoFrame(data: ByteArray, ptsUs: Long, key: Boolean) {
        synchronized(lock) {
            val codec = decoder ?: return
            if (waitingForKeyFrame && !key) return
            try {
                val index = codec.dequeueInputBuffer(20_000)
                if (index < 0) {
                    // Decoder is behind: drop and resynchronise on the next key frame.
                    waitingForKeyFrame = true
                    requestKeyFrame()
                    return
                }
                val buf = codec.getInputBuffer(index) ?: return
                buf.clear()
                if (data.size > buf.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    waitingForKeyFrame = true
                    return
                }
                buf.put(data)
                codec.queueInputBuffer(index, 0, data.size, ptsUs, 0)
                waitingForKeyFrame = false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "decoder rejected frame; restarting", e)
                rebuildDecoderLocked()
            }
        }
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!closed) {
            try {
                val index = codec.dequeueOutputBuffer(info, 50_000)
                if (index >= 0) codec.releaseOutputBuffer(index, true)
            } catch (e: IllegalStateException) {
                break // codec released
            }
        }
    }

    /** Splits Annex-B SPS/PPS so each can be passed as csd-0/csd-1. */
    private fun splitParameterSets(data: ByteArray): Pair<ByteArray, ByteArray?> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                (data[i + 2].toInt() == 1 || (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))
            ) {
                starts += i
                i += if (data[i + 2].toInt() == 1) 3 else 4
            } else {
                i++
            }
        }
        if (starts.size < 2) return data to null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for ((n, start) in starts.withIndex()) {
            val end = if (n + 1 < starts.size) starts[n + 1] else data.size
            val headerLen = if (data[start + 2].toInt() == 1) 3 else 4
            val nalType = data[start + headerLen].toInt() and 0x1F
            val unit = data.copyOfRange(start, end)
            if (nalType == 7 && sps == null) sps = unit
            if (nalType == 8 && pps == null) pps = unit
        }
        return if (sps != null && pps != null) sps to pps else data to null
    }

    // ---- Audio ----

    private fun onAudioConfig(data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        val rate = buf.int
        val channels = buf.get().toInt()
        audioThread?.interrupt()
        audioQueue.clear()
        audioBytesPerSecond = rate * channels * 2
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuf * 2, audioBytesPerSecond / 10),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack = track
        val thread = Thread({ audioLoop(track) }, "mirror-audio")
        audioThread = thread
        thread.start()
    }

    private fun onAudioFrame(data: ByteArray) {
        if (audioTrack == null) return
        if (!audioQueue.offer(data)) {
            audioQueue.clear() // far behind: jump to live
            audioQueue.offer(data)
        }
    }

    private fun audioLoop(track: AudioTrack) {
        try {
            track.play()
            var started = false
            while (!closed && !Thread.currentThread().isInterrupted) {
                // Wait for a small cushion before starting to avoid immediate underruns.
                if (!started) {
                    if (queuedAudioBytes() < audioBytesPerSecond / 25) {
                        Thread.sleep(5)
                        continue
                    }
                    started = true
                }
                // If more than ~250 ms piles up, drop old audio to stay in sync with video.
                while (queuedAudioBytes() > audioBytesPerSecond / 4) audioQueue.poll()
                val chunk = audioQueue.poll(500, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    started = false // sender paused audio; rebuild the cushion
                    continue
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "audio playback failed", e)
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    private fun queuedAudioBytes(): Int = audioQueue.sumOf { it.size }

    companion object {
        private const val TAG = "MirrorSession"
    }
}
