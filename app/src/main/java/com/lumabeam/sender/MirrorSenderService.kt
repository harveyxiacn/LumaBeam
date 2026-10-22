package com.lumabeam.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.lumabeam.R

/** Foreground service that owns the screen capture while mirroring. */
class MirrorSenderService : Service(), MirrorStreamer.Listener {
    private var projection: MediaProjection? = null
    private var streamer: MirrorStreamer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent == null || streamer != null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val quality = Quality.values().getOrElse(intent.getIntExtra(EXTRA_QUALITY, 1)) { Quality.HD }
        val audio = intent.getBooleanExtra(EXTRA_AUDIO, true)
        tvName = intent.getStringExtra(EXTRA_TV_NAME) ?: host
        if (data == null || host == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be in the foreground with the mediaProjection type before obtaining the projection.
        goForeground()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "cannot start projection", e)
            null
        }
        if (mp == null) {
            onStatus(MirrorStreamer.Status.STOPPED, "denied")
            shutdown()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // User revoked capture from the system UI.
                Handler(Looper.getMainLooper()).post { shutdown() }
            }
        }, Handler(Looper.getMainLooper()))

        acquireLocks()
        streamer = MirrorStreamer(this, mp, host, port, quality, audio, this).also { it.start() }
        return START_NOT_STICKY
    }

    override fun onStatus(status: MirrorStreamer.Status, detail: String) {
        lastStatus = status
        lastDetail = detail
        listener?.onStatus(status, detail)
        if (status == MirrorStreamer.Status.STOPPED) shutdown()
    }

    private fun shutdown() {
        streamer?.stop()
        streamer = null
        projection?.stop()
        projection = null
        wifiLock?.takeIf { it.isHeld }?.release()
        cpuLock?.takeIf { it.isHeld }?.release()
        if (lastStatus != MirrorStreamer.Status.STOPPED) {
            lastStatus = MirrorStreamer.Status.STOPPED
            listener?.onStatus(MirrorStreamer.Status.STOPPED, "")
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        if (streamer != null) shutdown()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "lumabeam-mirror").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Keeps streaming if the phone screen turns off (e.g. audio-only mirroring).
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumabeam:mirror").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.sender_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SenderActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorSenderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mirroring_to, tvName ?: ""))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(R.drawable.ic_notification, getString(R.string.stop), stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        private const val TAG = "MirrorSender"
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.lumabeam.STOP_MIRROR"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_TV_NAME = "tvName"

        /** UI observer; set by [SenderActivity] while visible. Main thread only. */
        var listener: MirrorStreamer.Listener? = null
        var lastStatus = MirrorStreamer.Status.STOPPED
            private set
        var lastDetail = ""
            private set
        var tvName: String? = null
            private set

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorSenderService::class.java).setAction(ACTION_STOP))
        }
    }
}
