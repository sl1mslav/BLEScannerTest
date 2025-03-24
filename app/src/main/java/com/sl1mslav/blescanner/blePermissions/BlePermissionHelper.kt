package com.sl1mslav.blescanner.blePermissions

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sl1mslav.blescanner.screens.main.BlePermission

fun collectRequiredPermissions(context: Context): List<BlePermission> {
    return buildList {
        addAll(
            listOf(
                BlePermission(
                    manifestName = ACCESS_COARSE_LOCATION,
                    readableName = "Приблизительное местоположение",
                    isGranted = false
                ),
                BlePermission(
                    manifestName = ACCESS_FINE_LOCATION,
                    readableName = "Точное местоположение",
                    isGranted = false
                )
            )
        )

        // Android 10
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                BlePermission(
                    manifestName = ACCESS_BACKGROUND_LOCATION,
                    readableName = "Местоположение в фоне",
                    isGranted = false
                )
            )
        }

        // Android 12
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addAll(
                listOf(
                    BlePermission(
                        manifestName = BLUETOOTH_CONNECT,
                        readableName = "Bluetooth: подключение",
                        isGranted = false
                    ),
                    BlePermission(
                        manifestName = BLUETOOTH_ADVERTISE,
                        readableName = "Bluetooth: вещание",
                        isGranted = false
                    ),
                    BlePermission(
                        manifestName = BLUETOOTH_SCAN,
                        readableName = "Bluetooth: сканирование",
                        isGranted = false
                    )
                )
            )
        }

        // Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                BlePermission(
                    manifestName = POST_NOTIFICATIONS,
                    readableName = "Уведомления",
                    isGranted = false
                )
            )
        }
    }.map {
        it.copy(
            isGranted = ContextCompat.checkSelfPermission(
                context,
                it.manifestName
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
}