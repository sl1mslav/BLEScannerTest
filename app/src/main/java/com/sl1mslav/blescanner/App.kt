package com.sl1mslav.blescanner

import android.app.Application
import com.sl1mslav.blescanner.logger.Logger

class App: Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.init(applicationContext)
    }
}