package com.sl1mslav.blescanner.screens.main

import androidx.compose.runtime.Immutable

@Immutable
data class MainScreenState(
    val isBluetoothEnabled: Boolean,
    val isLocationEnabled: Boolean,
    val permissions: List<BlePermission>,
    val isServiceRunning: Boolean,
    val ignoresDozeMode: Boolean,
    val needsAutoStart: Boolean
)

data class BlePermission(
    val manifestName: String,
    val readableName: String,
    val isGranted: Boolean
)
