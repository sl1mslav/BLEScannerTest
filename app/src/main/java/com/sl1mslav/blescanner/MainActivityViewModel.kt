package com.sl1mslav.blescanner

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.scanner.BleScanner
import com.sl1mslav.blescanner.screens.BlePermission
import com.sl1mslav.blescanner.screens.MainScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class MainActivityViewModel(
    availabilityTracker: BleAvailabilityObserver,
    isServiceRunningInitial: Boolean,
    initialPermissions: List<BlePermission>,
    ignoresDozeModeInitial: Boolean
) : ViewModel() {
    private val permissions: MutableStateFlow<List<BlePermission>> = MutableStateFlow(emptyList())
    private val isServiceRunning: MutableStateFlow<Boolean> =
        MutableStateFlow(isServiceRunningInitial)
    private val ignoresDozeMode: MutableStateFlow<Boolean> = MutableStateFlow(ignoresDozeModeInitial)

    private val rssi = MutableStateFlow(BleScanner.DEFAULT_TARGET_RSSI)

    val state = combine(
        availabilityTracker.bleAvailability,
        permissions,
        isServiceRunning,
        ignoresDozeMode,
        rssi
    ) { bleAvailability, permissions, isServiceRunning, ignoresDozeMode, rssi ->
        MainScreenState(
            isBluetoothEnabled = bleAvailability.isBluetoothEnabled,
            isLocationEnabled = bleAvailability.isLocationEnabled,
            permissions = permissions,
            isServiceRunning = isServiceRunning,
            ignoresDozeMode = ignoresDozeMode,
            needsAutoStart = false,
            currentRssi = rssi
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MainScreenState(
            isBluetoothEnabled = availabilityTracker.bleAvailability.value.isBluetoothEnabled,
            isLocationEnabled = availabilityTracker.bleAvailability.value.isLocationEnabled,
            permissions = initialPermissions,
            isServiceRunning = isServiceRunningInitial,
            ignoresDozeMode = ignoresDozeModeInitial,
            needsAutoStart = false,
            currentRssi = BleScanner.DEFAULT_TARGET_RSSI
        )
    )

    fun onNewRssi(newRssi: Int) {
        Log.d("RSSI", "newRssi: $newRssi")
        rssi.update { newRssi }
    }

    fun onChangeServiceState(isRunning: Boolean) {
        isServiceRunning.value = isRunning
    }

    fun onNewPermissions(newPermissions: List<BlePermission>) {
        permissions.value = newPermissions
    }

    fun onChangeDozeBehavior(isIgnoreEnabled: Boolean) {
        ignoresDozeMode.value = isIgnoreEnabled
    }

    class Factory(
        private val availabilityTracker: BleAvailabilityObserver,
        private val isServiceRunning: Boolean,
        private val initialPermissions: List<BlePermission>,
        private val ignoresDozeMode: Boolean
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainActivityViewModel(
                availabilityTracker,
                isServiceRunning,
                initialPermissions,
                ignoresDozeMode
            ) as T
        }
    }
}