package com.sl1mslav.blescanner.blePermissions

import android.Manifest
import android.os.Build

fun collectRequiredPermissions(): List<String> {
    return buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Android 10
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        // Android 12
        if (Build.VERSION.SDK_INT >=  Build.VERSION_CODES.S) {
            addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }

        // Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Android 14
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }
    }
}