package com.lumabeam.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.lumabeam.R
import com.lumabeam.core.Net
import com.lumabeam.receiver.TvActivity

/** Phone/tablet screen: pick a TV and mirror this device's screen and sound to it. */
class SenderActivity : Activity(), MirrorStreamer.Listener {
    private lateinit var discovery: TvDiscovery
    private lateinit var tvList: LinearLayout
    private lateinit var searching: TextView
    private lateinit var statusCard: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var manualIp: EditText
    private lateinit var quality: RadioGroup
    private lateinit var audio: CheckBox

    private var pendingTarget: TvTarget? = null

    private val prefs by lazy { getSharedPreferences("sender", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)
        tvList = findViewById(R.id.tvList)
        searching = findViewById(R.id.searching)
        statusCard = findViewById(R.id.statusCard)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        manualIp = findViewById(R.id.manualIp)
        quality = findViewById(R.id.quality)
        audio = findViewById(R.id.audio)

        manualIp.setText(prefs.getString("manual_ip", ""))
        quality.check(
            when (prefs.getInt("quality", 1)) {
                0 -> R.id.qualitySmooth
                2 -> R.id.qualityMax
                else -> R.id.qualityHd
            },
        )
        quality.setOnCheckedChangeListener { _, _ -> prefs.edit().putInt("quality", selectedQuality().ordinal).apply() }

        val audioNote = findViewById<TextView>(R.id.audioNote)
        if (Build.VERSION.SDK_INT >= 29) {
            audio.isChecked = prefs.getBoolean("audio", true)
            audio.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("audio", checked).apply() }
            audioNote.setText(R.string.audio_note)
        } else {
            audio.isChecked = false
            audio.isEnabled = false
            audioNote.setText(R.string.audio_unsupported)
        }

        findViewById<Button>(R.id.manualConnect).setOnClickListener {
            val ip = manualIp.text.toString().trim()
            if (!ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                Toast.makeText(this, R.string.bad_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("manual_ip", ip).apply()
            beginMirroring(TvTarget(ip, ip, Net.MIRROR_PORT))
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener { MirrorSenderService.stop(this) }
        findViewById<Button>(R.id.useAsReceiver).setOnClickListener {
            startActivity(Intent(this, TvActivity::class.java))
        }

        discovery = TvDiscovery(this, ::showTvs)
    }

    override fun onStart() {
        super.onStart()
        MirrorSenderService.listener = this
        onStatus(MirrorSenderService.lastStatus, MirrorSenderService.lastDetail)
        discovery.start()
    }

    override fun onStop() {
        super.onStop()
        if (MirrorSenderService.listener === this) MirrorSenderService.listener = null
        discovery.stop()
    }

    private fun selectedQuality(): Quality = when (quality.checkedRadioButtonId) {
        R.id.qualitySmooth -> Quality.SMOOTH
        R.id.qualityMax -> Quality.MAX
        else -> Quality.HD
    }

    private fun showTvs(tvs: List<TvTarget>) {
        tvList.removeAllViews()
        searching.setText(if (tvs.isEmpty()) R.string.searching else R.string.tap_to_mirror)
        for (tv in tvs) {
            val button = layoutInflater.inflate(R.layout.item_tv, tvList, false) as Button
            button.text = getString(R.string.tv_entry, tv.name, tv.host)
            button.setOnClickListener { beginMirroring(tv) }
            tvList.addView(button)
        }
    }

    // ---- Start flow: permissions -> screen capture consent -> service ----

    private fun beginMirroring(target: TvTarget) {
        if (MirrorSenderService.lastStatus != MirrorStreamer.Status.STOPPED) {
            Toast.makeText(this, R.string.already_mirroring, Toast.LENGTH_SHORT).show()
            return
        }
        pendingTarget = target
        val needed = mutableListOf<String>()
        if (audio.isChecked && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            requestCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val audioDenied = permissions.indices.any {
            permissions[it] == Manifest.permission.RECORD_AUDIO && grantResults[it] != PackageManager.PERMISSION_GRANTED
        }
        if (audioDenied) {
            audio.isChecked = false
            Toast.makeText(this, R.string.audio_denied, Toast.LENGTH_LONG).show()
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val target = pendingTarget ?: return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MirrorSenderService::class.java)
            .putExtra(MirrorSenderService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MirrorSenderService.EXTRA_DATA, data)
            .putExtra(MirrorSenderService.EXTRA_HOST, target.host)
            .putExtra(MirrorSenderService.EXTRA_PORT, target.port)
            .putExtra(MirrorSenderService.EXTRA_TV_NAME, target.name)
            .putExtra(MirrorSenderService.EXTRA_QUALITY, selectedQuality().ordinal)
            .putExtra(MirrorSenderService.EXTRA_AUDIO, audio.isChecked)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        onStatus(MirrorStreamer.Status.CONNECTING, target.host)
    }

    // ---- Status ----

    override fun onStatus(status: MirrorStreamer.Status, detail: String) {
        val tv = MirrorSenderService.tvName ?: ""
        when (status) {
            MirrorStreamer.Status.STOPPED -> {
                statusCard.visibility = View.GONE
                when (detail) {
                    "ended_by_tv" -> Toast.makeText(this, R.string.ended_by_tv, Toast.LENGTH_LONG).show()
                    "unreachable" -> Toast.makeText(this, R.string.tv_unreachable, Toast.LENGTH_LONG).show()
                }
            }
            MirrorStreamer.Status.CONNECTING -> {
                statusCard.visibility = View.VISIBLE
                statusTitle.text = getString(R.string.connecting_to, tv)
                statusDetail.text = ""
            }
            MirrorStreamer.Status.RECONNECTING -> {
                statusCard.visibility = View.VISIBLE
                statusTitle.text = getString(R.string.reconnecting_to, tv)
                statusDetail.setText(R.string.reconnecting_detail)
            }
            MirrorStreamer.Status.STREAMING -> {
                statusCard.visibility = View.VISIBLE
                statusTitle.text = getString(R.string.mirroring_to, tv)
                if (detail.contains("Mbps")) statusDetail.text = detail
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_CAPTURE = 2
    }
}
