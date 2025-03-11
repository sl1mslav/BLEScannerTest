package com.sl1mslav.blescanner.caching

import android.content.Context
import com.google.gson.GsonBuilder
import com.sl1mslav.blescanner.scanner.model.BleDevice

class DevicesPrefsCachingService(context: Context) {
    private val prefs = context.getSharedPreferences(
        DEVICES_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val gson = GsonBuilder().create()

    fun getSavedDevices(): List<BleDevice> {
        val deviceElements = prefs.getStringSet(DEVICES_KEY, emptySet())
        return deviceElements
            ?.map { element -> gson.fromJson(element, BleDevice::class.java)
            }.orEmpty()
    }

    fun saveDevices(bleDevice: List<BleDevice>) {
        val deviceElements = bleDevice.map { device -> gson.toJson(device) }.toSet()
        prefs.edit().putStringSet(DEVICES_KEY, deviceElements).apply()
    }

    fun getPreferredRssi(): Int {
        return prefs.getInt(RSSI_KEY, 0)
    }

    fun savePreferredRssi(rssi: Int) {
        prefs.edit().putInt(RSSI_KEY, rssi).apply()
    }

    companion object {
        private const val DEVICES_PREFS_NAME = "devices_preferences"
        private const val DEVICES_KEY = "devices"
        private const val RSSI_KEY = "rssi"
    }
}