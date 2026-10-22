package com.lumabeam.sender

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.lumabeam.core.MirrorProtocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/** Streaming presets. Frame rate is an upper bound; static screens send far less. */
enum class Quality(val maxLongSide: Int, val bitrate: Int, val fps: Int) {
    SMOOTH(1280, 5_000_000, 60),
    HD(1920, 10_000_000, 60),
    MAX(2560, 20_000_000, 60),
}

/**
 * Captures the phone screen (and, on Android 10+, its audio) and streams it to
 * a TV. Survives Wi-Fi hiccups by reconnecting automatically while keeping the
 * screen capture alive, follows rotation, and adapts bitrate to the link.
 */
class MirrorStreamer(
    private val context: Context,
    private val projection: MediaProjection,
    private val host: String,
    private val port: Int,
    private val quality: Quality,
    private val captureAudio: Boolean,
    private val listener: Listener,
) {
    enum class Status { CONNECTING, STREAMING, RECONNECTING, STOPPED }

    interface Listener {
        /** Called on the main thread. */
        fun onStatus(status: Status, detail: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var endedByTv = false

    private val videoLock = Object()
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encodedLandscape = false
    private var videoWidth = 0
    private var videoHeight = 0

    @Volatile private var queue: SendQueue? = null
    @Volatile private var socket: Socket? = null
    private var receiverLongSide = 1920

    @Volatile private var bitrate = quality.bitrate
    private var lastBitrateDrop = 0L

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun start() {
        running = true
        displayManager.registerDisplayListener(displayListener, main)
        if (captureAudio) startAudio()
        Thread(::connectionLoop, "mirror-main").start()
        main.postDelayed(statsTick, 1000)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(statsTick)
        displayManager.unregisterDisplayListener(displayListener)
        queue?.offerControl(MirrorProtocol.BYE)
        // Give the BYE a moment to go out, then tear everything down.
        Thread {
            Thread.sleep(150)
            closeConnection()
            synchronized(videoLock) {
                stopVideoLocked()
                virtualDisplay?.release()
                virtualDisplay = null
            }
            stopAudio()
        }.start()
    }

    private fun report(status: Status, detail: String = "") = main.post { listener.onStatus(status, detail) }

    // ---- Connection management ----

    private fun connectionLoop() {
        var failures = 0
        var firstAttemptAt = System.currentTimeMillis()
        while (running) {
            report(if (failures == 0) Status.CONNECTING else Status.RECONNECTING, host)
            try {
                runSession()
                failures = 0
                firstAttemptAt = System.currentTimeMillis()
            } catch (e: IOException) {
                Log.i(TAG, "link lost: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "session failed", e)
            } finally {
                closeConnection()
                synchronized(videoLock) { stopVideoLocked() }
            }
            if (endedByTv) {
                running = false
                report(Status.STOPPED, "ended_by_tv")
                break
            }
            if (!running) break
            failures++
            // Keep retrying for a while: Wi-Fi roaming or a TV input switch should not end the session.
            if (System.currentTimeMillis() - firstAttemptAt > RECONNECT_WINDOW_MS) {
                running = false
                report(Status.STOPPED, "unreachable")
                break
            }
            try {
                Thread.sleep(minOf(500L * failures, 2000L))
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun runSession() {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.sendBufferSize = 256 * 1024
        try {
            s.trafficClass = 0xB8 // DSCP EF: ask Wi-Fi to treat this as real-time traffic
        } catch (_: Exception) {
        }
        s.connect(InetSocketAddress(host, port), 4000)
        s.soTimeout = 10_000
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
        MirrorProtocol.writeHello(
            output,
            JSONObject().put("name", Build.MODEL ?: "Android").put("version", MirrorProtocol.VERSION),
        )
        val hello = MirrorProtocol.readHello(input)
        receiverLongSide = maxOf(hello.optInt("width", 1920), hello.optInt("height", 1080), 1280)
        val tvName = hello.optString("name", host)

        val q = SendQueue(congestionCallbacks)
        q.videoBitrate = bitrate
        queue = q
        audioRecord?.let { rec ->
            q.audioBytesPerSecond = rec.sampleRate * rec.channelCount * 2
            q.offerConfig(
                MirrorProtocol.AUDIO_CONFIG,
                ByteBuffer.allocate(5).putInt(rec.sampleRate).put(rec.channelCount.toByte()).array(),
            )
        }

        Thread({ readLoop(input, q) }, "mirror-reader").start()
        synchronized(videoLock) { startVideoLocked() }
        report(Status.STREAMING, tvName)
        q.writeLoop(output)
        if (running && !endedByTv) throw IOException("connection closed")
    }

    private fun readLoop(input: DataInputStream, q: SendQueue) {
        try {
            while (true) {
                val p = MirrorProtocol.readPacket(input)
                when (p.type) {
                    MirrorProtocol.REQUEST_KEYFRAME -> requestKeyFrame()
                    MirrorProtocol.BYE -> {
                        endedByTv = true
                        break
                    }
                }
            }
        } catch (e: IOException) {
            // Socket closed or timed out (no pong): the main loop will reconnect.
        } finally {
            q.close()
        }
    }

    private fun closeConnection() {
        queue?.close()
        queue = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    // ---- Adaptive bitrate ----

    private val congestionCallbacks = object : SendQueue.Callbacks {
        override fun onCongestion() {
            requestKeyFrame()
            val now = System.currentTimeMillis()
            if (now - lastBitrateDrop < 1000) return
            lastBitrateDrop = now
            setBitrate(maxOf(MIN_BITRATE, bitrate * 7 / 10))
        }

        override fun onHealthy() {
            if (bitrate < quality.bitrate) setBitrate(minOf(quality.bitrate, bitrate * 115 / 100))
        }
    }

    private fun setBitrate(value: Int) {
        if (value == bitrate) return
        bitrate = value
        queue?.videoBitrate = value
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value) })
        } catch (_: IllegalStateException) {
        }
    }

    private fun requestKeyFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
        }
    }

    // ---- Video ----

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }

    private fun startVideoLocked() {
        val metrics = realMetrics()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        // Largest size within the preset, the TV's resolution and the encoder's limits.
        var longSide = minOf(quality.maxLongSide, receiverLongSide)
        var w: Int
        var h: Int
        while (true) {
            val scale = minOf(1f, longSide.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
            w = align16(metrics.widthPixels * scale)
            h = align16(metrics.heightPixels * scale)
            if (caps.isSizeSupported(w, h) || longSide <= 640) break
            longSide -= 320
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Re-send the last frame if the screen is static, so the TV never stalls on a missing frame.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (caps.isSizeSupported(w, h).not()) Log.w(TAG, "encoder may not support ${w}x$h")
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "LumaBeamMirror", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )
        } else {
            vd.resize(w, h, metrics.densityDpi)
            vd.surface = surface
        }
        encoder = codec
        inputSurface = surface
        videoWidth = w
        videoHeight = h
        encodedLandscape = w > h
        Thread({ drainLoop(codec, w, h) }, "mirror-encoder").start()
    }

    private fun stopVideoLocked() {
        virtualDisplay?.surface = null
        val codec = encoder ?: return
        encoder = null
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        inputSurface?.release()
        inputSurface = null
    }

    private fun drainLoop(codec: MediaCodec, w: Int, h: Int) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                break // encoder stopped (rotation or disconnect)
            }
            if (index < 0) {
                if (encoder !== codec) break
                continue
            }
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val q = queue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val payload = ByteBuffer.allocate(8 + info.size).putInt(w).putInt(h)
                        payload.put(buf)
                        q?.offerConfig(MirrorProtocol.VIDEO_CONFIG, payload.array())
                    } else if (q != null) {
                        val data = ByteArray(info.size)
                        buf.get(data)
                        q.offerVideo(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            val m = realMetrics()
            val landscape = m.widthPixels > m.heightPixels
            if (landscape == encodedLandscape) return
            encodedLandscape = landscape
            // Rotation: rebuild the encoder with swapped dimensions; the TV re-letterboxes on the new config.
            Thread({
                synchronized(videoLock) {
                    if (encoder != null && running) {
                        stopVideoLocked()
                        startVideoLocked()
                    }
                }
            }, "mirror-rotate").start()
        }
    }

    private fun align16(v: Float): Int = maxOf(16, (v.toInt() / 16) * 16)

    // ---- Audio (Android 10+) ----

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE * 4 / 10))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record.startRecording()
            audioRecord = record
            val thread = Thread({ audioLoop(record) }, "mirror-audio")
            audioThread = thread
            thread.start()
        } catch (e: Exception) {
            Log.w(TAG, "audio capture unavailable: ${e.message}")
        }
    }

    private fun audioLoop(record: AudioRecord) {
        val chunk = SAMPLE_RATE / 100 * 4 // 10 ms of 16-bit stereo
        val buf = ByteArray(chunk)
        try {
            while (running) {
                var filled = 0
                while (filled < chunk && running) {
                    val n = record.read(buf, filled, chunk - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled <= 0) {
                    Thread.sleep(5)
                    continue
                }
                queue?.offerAudio(buf.copyOf(filled), System.nanoTime() / 1000)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped: ${e.message}")
        }
    }

    private fun stopAudio() {
        audioThread?.interrupt()
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        audioRecord = null
    }

    // ---- Stats ----

    private var lastSent = 0L
    private var lastQueue: SendQueue? = null

    private val statsTick = object : Runnable {
        override fun run() {
            val q = queue
            if (q != null) {
                if (q !== lastQueue) {
                    lastQueue = q
                    lastSent = 0
                }
                val sent = q.sentBytes
                val mbps = (sent - lastSent) * 8 / 1_000_000.0
                lastSent = sent
                listener.onStatus(
                    Status.STREAMING,
                    String.format(java.util.Locale.US, "%d×%d · %.1f Mbps", videoWidth, videoHeight, mbps),
                )
            }
            if (running) main.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val TAG = "MirrorStreamer"
        private const val SAMPLE_RATE = 48_000
        private const val MIN_BITRATE = 1_500_000
        private const val RECONNECT_WINDOW_MS = 60_000L
    }
}
