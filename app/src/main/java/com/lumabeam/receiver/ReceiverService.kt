package com.lumabeam.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.lumabeam.R
import com.lumabeam.core.HttpResponse
import com.lumabeam.core.HttpServer
import com.lumabeam.core.Net
import com.lumabeam.receiver.dlna.DlnaRenderer

/**
 * Keeps every receiver running while the TV is on: mirroring server, DLNA
 * renderer (SSDP + HTTP) and the browser-cast web page.
 */
class ReceiverService : Service() {
    private var http: HttpServer? = null
    private var mirror: MirrorServer? = null
    private var dlna: DlnaRenderer? = null
    private var web: WebRemote? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReceiverHub.init(this)
        goForeground()
        acquireLocks()
        startServers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            stopServers()
            goForeground()
            startServers()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServers()
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.receiver_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.receiver_ready, Net.deviceName(this)))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Needed to receive SSDP multicast on Wi-Fi.
        multicastLock = wifi.createMulticastLock("lumabeam-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Keep the Wi-Fi radio out of power-save so mirroring stays smooth.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "lumabeam-stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startServers() {
        val dlnaRenderer = DlnaRenderer(this)
        val webRemote = WebRemote(this)
        dlna = dlnaRenderer
        web = webRemote
        try {
            http = HttpServer(Net.HTTP_PORT) { req ->
                dlnaRenderer.handle(req) ?: webRemote.handle(req) ?: HttpResponse.text(404, "Not found")
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server failed to start", e)
        }
        try {
            dlnaRenderer.start()
        } catch (e: Exception) {
            Log.e(TAG, "DLNA failed to start", e)
        }
        try {
            mirror = MirrorServer(this).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "mirror server failed to start", e)
        }
        registerNsd()
    }

    private fun stopServers() {
        nsdListener?.let {
            try {
                (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it)
            } catch (_: Exception) {
            }
        }
        nsdListener = null
        mirror?.stop()
        dlna?.stop()
        http?.stop()
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = Net.deviceName(this@ReceiverService)
            serviceType = Net.NSD_TYPE
            port = Net.MIRROR_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD registered ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "NSD failed $error")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        try {
            (getSystemService(NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "NSD unavailable: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_RESTART = "com.lumabeam.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Re-advertises everything, e.g. after the device was renamed. */
        fun restart(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_RESTART))
        }
    }
}
