package com.sl1mslav.blescanner.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sl1mslav.blescanner.caching.DevicesPrefsCachingService
import com.sl1mslav.blescanner.logger.Logger

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Logger.log("received boot flag")
            if (DevicesPrefsCachingService(context).getSavedDevices().isNotEmpty()) {
                context.startService(Intent(context, BleScannerService::class.java))
            }
        } catch (e: Exception) {
            Logger.log(
                "exception during action ${intent.action}",
                e = e
            )
        }
    }
}