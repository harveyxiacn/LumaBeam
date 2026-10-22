package com.lumabeam

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import com.lumabeam.receiver.TvActivity
import com.lumabeam.sender.SenderActivity

/** Launcher entry: TVs become receivers, phones and tablets become senders. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (isReceiverDevice(this)) TvActivity::class.java else SenderActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }

    companion object {
        fun isReceiverDevice(context: Context): Boolean {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            val pm = context.packageManager
            return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }
    }
}
