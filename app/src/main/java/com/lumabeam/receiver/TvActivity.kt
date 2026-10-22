package com.lumabeam.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lumabeam.R
import com.lumabeam.core.Net
import com.lumabeam.core.QrCode
import com.lumabeam.receiver.dlna.Soap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The TV screen: a home screen with connection instructions, plus full-screen
 * layers for screen mirroring and for cast media (video, music, photos).
 */
class TvActivity : Activity(), MediaSurface {
    private val main = Handler(Looper.getMainLooper())

    private lateinit var idle: View
    private lateinit var deviceName: TextView
    private lateinit var networkInfo: TextView
    private lateinit var qr: ImageView
    private lateinit var mirrorSurface: SurfaceView
    private lateinit var mediaLayer: FrameLayout
    private lateinit var videoSurface: SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var audioPanel: View
    private lateinit var audioTitle: TextView
    private lateinit var loading: ProgressBar
    private lateinit var osd: View
    private lateinit var osdTitle: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var osdTime: TextView
    private lateinit var banner: TextView

    private var player: MediaPlayer? = null
    private var prepared = false
    private var playWhenReady = false
    private var item: MediaItem? = null
    private var videoHolderReady = false
    private var videoWidth = 0
    private var videoHeight = 0

    private var mirror: MirrorSession? = null
    private var mirrorHolderReady = false

    private var shownIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        ReceiverHub.init(this)
        ReceiverService.start(this)

        idle = findViewById(R.id.idle)
        deviceName = findViewById(R.id.deviceName)
        networkInfo = findViewById(R.id.networkInfo)
        qr = findViewById(R.id.qr)
        mirrorSurface = findViewById(R.id.mirrorSurface)
        mediaLayer = findViewById(R.id.mediaLayer)
        videoSurface = findViewById(R.id.videoSurface)
        imageView = findViewById(R.id.imageView)
        audioPanel = findViewById(R.id.audioPanel)
        audioTitle = findViewById(R.id.audioTitle)
        loading = findViewById(R.id.loading)
        osd = findViewById(R.id.osd)
        osdTitle = findViewById(R.id.osdTitle)
        osdProgress = findViewById(R.id.osdProgress)
        osdTime = findViewById(R.id.osdTime)
        banner = findViewById(R.id.banner)

        findViewById<Button>(R.id.renameButton).setOnClickListener { rename() }
        val overlay = findViewById<Button>(R.id.overlayButton)
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            overlay.setOnClickListener { requestOverlay() }
        } else {
            overlay.visibility = View.GONE
        }
        findViewById<Button>(R.id.renameButton).requestFocus()

        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                videoHolderReady = true
                player?.setDisplay(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoHolderReady = false
                try {
                    player?.setDisplay(null)
                } catch (_: Exception) {
                }
            }
        })

        mirrorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mirrorHolderReady = true
                mirror?.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mirrorHolderReady = false
                mirror?.detachSurface()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        refreshHome()
        ReceiverHub.attach(this)
        main.post(tick)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(tick)
        ReceiverHub.detach(this)
        // Leaving the app stops cast media; the servers keep running in the service.
        if (item != null) {
            releasePlayer()
            hideMediaLayer()
            ReceiverHub.mediaClosed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    // ---- Home screen ----

    private fun refreshHome() {
        deviceName.text = Net.deviceName(this)
        val ip = Net.localIpv4()?.hostAddress
        if (ip == null) {
            networkInfo.text = getString(R.string.no_network)
            qr.setImageDrawable(null)
            shownIp = null
            return
        }
        val url = "http://$ip:${Net.HTTP_PORT}/"
        networkInfo.text = getString(R.string.network_info, ip)
        findViewById<TextView>(R.id.stepWeb).text = getString(R.string.tv_step_web_url, url)
        if (ip != shownIp) {
            qr.setImageBitmap(qrBitmap(url))
            shownIp = ip
        }
    }

    private fun qrBitmap(text: String): Bitmap {
        val code = QrCode.encode(text)
        val quiet = 2
        val n = code.size + quiet * 2
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (r in 0 until code.size) for (c in 0 until code.size) {
            if (code.modules[r][c]) bmp.setPixel(c + quiet, r + quiet, Color.BLACK)
        }
        // Scale up without smoothing so modules stay crisp.
        return Bitmap.createScaledBitmap(bmp, n * 12, n * 12, false)
    }

    private fun rename() {
        val input = EditText(this)
        input.setText(Net.deviceName(this))
        input.setSelectAllOnFocus(true)
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Net.setDeviceName(this, input.text.toString())
                refreshHome()
                ReceiverService.restart(this) // re-advertise under the new name
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            showBanner(getString(R.string.overlay_unavailable))
        }
    }

    private fun showBanner(text: String, ms: Long = 4000) {
        banner.text = text
        banner.visibility = View.VISIBLE
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, ms)
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    // ---- Periodic UI/state refresh ----

    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            val p = player
            if (p != null && prepared) {
                try {
                    ReceiverHub.positionMs = p.currentPosition.toLong()
                    ReceiverHub.durationMs = p.duration.toLong().coerceAtLeast(0)
                } catch (_: IllegalStateException) {
                }
                updateOsd()
            }
            if (tickCount % 10 == 0 && idle.visibility == View.VISIBLE) refreshHome()
            main.postDelayed(this, 500)
        }
    }

    private fun updateOsd() {
        val dur = ReceiverHub.durationMs
        val pos = ReceiverHub.positionMs
        osdProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        val state = when (ReceiverHub.state) {
            TransportState.PAUSED_PLAYBACK -> getString(R.string.paused) + " · "
            else -> ""
        }
        osdTime.text = if (dur > 0) state + Soap.formatTime(pos) + " / " + Soap.formatTime(dur) else state
    }

    private fun flashOsd(ms: Long = 4000) {
        osd.visibility = View.VISIBLE
        main.removeCallbacks(hideOsd)
        if (ReceiverHub.state != TransportState.PAUSED_PLAYBACK) main.postDelayed(hideOsd, ms)
    }

    private val hideOsd = Runnable {
        if (item?.kind == MediaKind.VIDEO || item?.kind == MediaKind.IMAGE) osd.visibility = View.GONE
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- MediaSurface: cast media ----

    override fun load(item: MediaItem, autoPlay: Boolean) {
        releasePlayer()
        this.item = item
        playWhenReady = autoPlay
        mediaLayer.visibility = View.VISIBLE
        idle.visibility = View.GONE
        keepScreenOn(true)
        osdTitle.text = item.title
        osdProgress.progress = 0
        osdTime.text = ""
        flashOsd()
        imageView.visibility = View.GONE
        audioPanel.visibility = View.GONE
        imageView.setImageDrawable(null)

        when (item.kind) {
            MediaKind.IMAGE -> loadImage(item)
            MediaKind.AUDIO -> {
                videoSurface.visibility = View.GONE
                audioPanel.visibility = View.VISIBLE
                audioTitle.text = item.title
                startPlayer(item)
            }
            MediaKind.VIDEO -> {
                videoSurface.visibility = View.VISIBLE
                startPlayer(item)
            }
        }
    }

    private fun startPlayer(item: MediaItem) {
        loading.visibility = View.VISIBLE
        prepared = false
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        if (item.kind == MediaKind.AUDIO) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE,
                    )
                    .build(),
            )
            if (item.uri.startsWith("file://")) {
                mp.setDataSource(item.uri.removePrefix("file://"))
            } else {
                mp.setDataSource(this, Uri.parse(item.uri), mapOf("User-Agent" to USER_AGENT))
            }
            if (item.kind == MediaKind.VIDEO && videoHolderReady) mp.setDisplay(videoSurface.holder)
            mp.setScreenOnWhilePlaying(true)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                loading.visibility = View.GONE
                ReceiverHub.durationMs = mp.duration.toLong().coerceAtLeast(0)
                if (playWhenReady) {
                    mp.start()
                    ReceiverHub.updateState(TransportState.PLAYING)
                } else {
                    ReceiverHub.updateState(TransportState.STOPPED)
                }
                updateOsd()
                flashOsd()
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                fitVideo(videoSurface, w, h)
            }
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> loading.visibility = View.VISIBLE
                    MediaPlayer.MEDIA_INFO_BUFFERING_END, MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START ->
                        loading.visibility = View.GONE
                }
                false
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                closeMedia()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (player === mp) {
                    showBanner(getString(R.string.cannot_play, item.title) + " ($what/$extra)", 6000)
                    closeMedia()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            showBanner(getString(R.string.cannot_play, item.title), 6000)
            closeMedia()
        }
    }

    private fun loadImage(item: MediaItem) {
        videoSurface.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        val metrics = resources.displayMetrics
        Thread {
            val bitmap = try {
                decodeImage(item.uri, maxOf(metrics.widthPixels, metrics.heightPixels))
            } catch (e: Exception) {
                null
            }
            main.post {
                if (this.item !== item) return@post
                loading.visibility = View.GONE
                if (bitmap == null) {
                    showBanner(getString(R.string.cannot_play, item.title), 6000)
                    closeMedia()
                } else {
                    imageView.setImageBitmap(bitmap)
                    ReceiverHub.updateState(TransportState.PLAYING)
                }
            }
        }.start()
    }

    /** Decodes an image, subsampled so it is no larger than needed for the screen. */
    private fun decodeImage(uri: String, maxSide: Int): Bitmap? {
        val bytes: ByteArray = if (uri.startsWith("file://")) {
            File(uri.removePrefix("file://")).readBytes()
        } else {
            val conn = URL(uri).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.inputStream.use { it.readBytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Letterboxes a SurfaceView to the content's aspect ratio. */
    private fun fitVideo(view: SurfaceView, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val parentW = (view.parent as View).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val parentH = (view.parent as View).height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val scale = minOf(parentW.toFloat() / w, parentH.toFloat() / h)
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.width = (w * scale).toInt()
        lp.height = (h * scale).toInt()
        lp.gravity = android.view.Gravity.CENTER
        view.layoutParams = lp
    }

    override fun play() {
        val p = player
        if (item?.kind == MediaKind.IMAGE) {
            ReceiverHub.updateState(TransportState.PLAYING)
            return
        }
        if (p == null) {
            ReceiverHub.current?.let { load(it, true) }
            return
        }
        playWhenReady = true
        if (prepared) {
            p.start()
            ReceiverHub.updateState(TransportState.PLAYING)
            flashOsd()
        } else {
            ReceiverHub.updateState(TransportState.TRANSITIONING)
        }
    }

    override fun pause() {
        playWhenReady = false
        val p = player ?: return
        if (prepared && p.isPlaying) {
            p.pause()
            ReceiverHub.updateState(TransportState.PAUSED_PLAYBACK)
            updateOsd()
            flashOsd()
        }
    }

    override fun stopMedia() {
        if (item == null) return
        releasePlayer()
        hideMediaLayer()
    }

    override fun seekTo(ms: Long) {
        val p = player ?: return
        if (!prepared) return
        p.seekTo(ms.coerceIn(0, p.duration.toLong().coerceAtLeast(0)).toInt())
        ReceiverHub.positionMs = ms
        updateOsd()
        flashOsd()
    }

    private fun closeMedia() {
        releasePlayer()
        hideMediaLayer()
        ReceiverHub.mediaClosed()
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.reset()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        prepared = false
    }

    private fun hideMediaLayer() {
        item = null
        mediaLayer.visibility = View.GONE
        loading.visibility = View.GONE
        imageView.setImageDrawable(null)
        if (mirror == null) {
            idle.visibility = View.VISIBLE
            keepScreenOn(false)
            refreshHome()
        }
    }

    // ---- MediaSurface: mirroring ----

    override fun showMirror(session: MirrorSession) {
        mirror?.takeIf { it !== session }?.detachSurface()
        mirror = session
        idle.visibility = View.GONE
        mirrorSurface.visibility = View.VISIBLE
        keepScreenOn(true)
        showBanner(getString(R.string.mirroring_from, session.senderName))
        session.onVideoSize = { w, h -> fitVideo(mirrorSurface, w, h) }
        if (session.videoWidth > 0) fitVideo(mirrorSurface, session.videoWidth, session.videoHeight)
        if (mirrorHolderReady) session.attachSurface(mirrorSurface.holder.surface)
    }

    override fun hideMirror(session: MirrorSession) {
        if (mirror !== session) return
        session.detachSurface()
        session.onVideoSize = null
        mirror = null
        mirrorSurface.visibility = View.GONE
        if (item == null) {
            idle.visibility = View.VISIBLE
            keepScreenOn(false)
            refreshHome()
        }
        showBanner(getString(R.string.mirroring_ended, session.senderName))
    }

    // ---- Remote control keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val hasMedia = item != null
        if (hasMedia) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_SPACE -> {
                    if (player?.isPlaying == true) ReceiverHub.pause() else ReceiverHub.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { ReceiverHub.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { ReceiverHub.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { ReceiverHub.stop(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekTo(ReceiverHub.positionMs + SEEK_STEP_MS); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekTo((ReceiverHub.positionMs - SEEK_STEP_MS).coerceAtLeast(0)); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                    updateOsd(); flashOsd(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            item != null -> ReceiverHub.stop()
            mirror != null -> mirror?.close()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val USER_AGENT = "LumaBeam/1.0 (Android) DLNADOC/1.50"
    }
}
