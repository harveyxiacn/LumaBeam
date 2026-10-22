package com.lumabeam.receiver

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** What kind of media a cast URI points at. */
enum class MediaKind { VIDEO, AUDIO, IMAGE }

data class MediaItem(
    val uri: String,
    val title: String,
    val kind: MediaKind,
    /** Raw DIDL-Lite metadata as sent by a DLNA controller (echoed back on GetMediaInfo). */
    val metadata: String = "",
    /** Label of who sent it, shown on screen. */
    val source: String = "",
)

/** UPnP AVTransport state names are used directly. */
enum class TransportState { NO_MEDIA_PRESENT, STOPPED, TRANSITIONING, PLAYING, PAUSED_PLAYBACK }

/** Playback surface implemented by [TvActivity]. All calls arrive on the main thread. */
interface MediaSurface {
    fun load(item: MediaItem, autoPlay: Boolean)
    fun play()
    fun pause()
    fun stopMedia()
    fun seekTo(ms: Long)
    fun showMirror(session: MirrorSession)
    fun hideMirror(session: MirrorSession)
}

/**
 * Process-wide glue between network servers (any thread) and the on-screen
 * player (main thread). Servers read the latest playback snapshot from here,
 * which the activity keeps up to date.
 */
object ReceiverHub {
    private const val TAG = "ReceiverHub"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    @Volatile var surface: MediaSurface? = null
        private set

    // Playback snapshot, written by the activity, read by DLNA/web handlers.
    @Volatile var current: MediaItem? = null
        private set
    @Volatile var state: TransportState = TransportState.NO_MEDIA_PRESENT
        private set
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0

    @Volatile var mirror: MirrorSession? = null
        private set

    private var pendingAutoPlay = false
    /** Media arrived while no activity was on screen; show it once one attaches. */
    private var pendingShow = false
    private val stateListeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addStateListener(l: () -> Unit) = synchronized(stateListeners) { stateListeners += l }
    fun removeStateListener(l: () -> Unit) = synchronized(stateListeners) { stateListeners -= l }

    fun updateState(newState: TransportState) {
        if (state == newState) return
        state = newState
        val copy = synchronized(stateListeners) { stateListeners.toList() }
        copy.forEach { it() }
    }

    // ---- Activity attachment ----

    fun attach(s: MediaSurface) {
        surface = s
        val item = current
        if (pendingShow && item != null) s.load(item, pendingAutoPlay)
        pendingShow = false
        mirror?.let { s.showMirror(it) }
    }

    fun detach(s: MediaSurface) {
        if (surface === s) surface = null
    }

    private fun ensureVisible() {
        if (surface != null) return
        try {
            val intent = Intent(appContext, TvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "cannot bring receiver to front: ${e.message}")
        }
    }

    // ---- Media commands (any thread) ----

    fun load(item: MediaItem, autoPlay: Boolean) {
        // Snapshot is updated synchronously so an immediately following Play/GetMediaInfo sees it.
        current = item
        positionMs = 0
        durationMs = 0
        updateState(if (autoPlay) TransportState.TRANSITIONING else TransportState.STOPPED)
        main.post {
            // A cast replaces an active mirror session, just as mirroring replaces a cast.
            mirror?.close()
            pendingAutoPlay = autoPlay
            val s = surface
            if (s != null) s.load(item, autoPlay) else {
                pendingShow = true
                ensureVisible()
            }
        }
    }

    fun play() {
        if (current != null && state != TransportState.PLAYING) updateState(TransportState.TRANSITIONING)
        main.post {
            if (current == null) return@post
            pendingAutoPlay = true
            val s = surface
            if (s != null) s.play() else {
                pendingShow = true
                ensureVisible()
            }
        }
    }

    fun pause() = main.post { pendingAutoPlay = false; surface?.pause() }

    fun stop() {
        main.post {
            pendingAutoPlay = false
            surface?.stopMedia()
            positionMs = 0
            updateState(if (current == null) TransportState.NO_MEDIA_PRESENT else TransportState.STOPPED)
        }
    }

    fun seek(ms: Long) = main.post { surface?.seekTo(ms) }

    /** Called by the activity when media ends or the user closes it. */
    fun mediaClosed() {
        pendingAutoPlay = false
        positionMs = 0
        updateState(TransportState.STOPPED)
    }

    // ---- Volume (system music stream) ----

    private val audio: AudioManager get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (percent.coerceIn(0, 100) * max + 50) / 100
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
    }

    fun isMuted(): Boolean = if (android.os.Build.VERSION.SDK_INT >= 23) {
        audio.isStreamMute(AudioManager.STREAM_MUSIC)
    } else false

    fun setMuted(mute: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0,
        )
    }

    // ---- Mirroring ----

    fun mirrorStarted(session: MirrorSession) {
        main.post {
            val old = mirror
            mirror = session
            if (old != null && old !== session) {
                surface?.hideMirror(old)
                old.close()
            }
            // A mirror session takes over the screen from any cast media.
            if (current != null) {
                pendingShow = false
                surface?.stopMedia()
                mediaClosed()
            }
            val s = surface
            if (s != null) s.showMirror(session) else ensureVisible()
        }
    }

    fun mirrorEnded(session: MirrorSession) {
        main.post {
            if (mirror === session) mirror = null
            surface?.hideMirror(session)
        }
    }
}
