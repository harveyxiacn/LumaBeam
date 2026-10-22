package com.lumabeam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lumabeam.MainActivity

/** Starts the receivers when the TV boots, so phones can cast without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MainActivity.isReceiverDevice(context)) return
        try {
            ReceiverService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "could not start receiver: ${e.message}")
        }
    }
}
