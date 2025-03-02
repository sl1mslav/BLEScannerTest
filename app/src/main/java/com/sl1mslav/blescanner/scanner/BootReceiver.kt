package com.sl1mslav.blescanner.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.d("BootReceiver", "onReceive: received boot flag")
            context.startService(Intent(context, BleScannerService::class.java))
        } catch (e: Exception) {
            Log.e(
                "BootReceiver",
                "onReceive: exception ${e.message}; action: ${intent.action}"
            )
        }
    }
}