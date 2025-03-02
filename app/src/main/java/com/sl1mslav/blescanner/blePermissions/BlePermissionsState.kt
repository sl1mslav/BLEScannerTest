package com.sl1mslav.blescanner.blePermissions

data class BlePermissionsState(
    val areNotificationsGranted: Boolean,
    val areBluetoothPermissionsGranted: Boolean,
    val isBackgroundLocationGranted: Boolean
)