package com.sl1mslav.blescanner.screens

import androidx.compose.runtime.Immutable

@Immutable
data class MainScreenState(
    val isBluetoothEnabled: Boolean,
    val isLocationEnabled: Boolean,
    val permissions: List<BlePermission>,
    val isServiceRunning: Boolean
)

data class BlePermission(
    val manifestName: String,
    val readableName: String,
    val isGranted: Boolean
)
