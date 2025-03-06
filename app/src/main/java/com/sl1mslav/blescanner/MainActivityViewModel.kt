package com.sl1mslav.blescanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.screens.BlePermission
import com.sl1mslav.blescanner.screens.MainScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class MainActivityViewModel(
    availabilityTracker: BleAvailabilityObserver,
    isServiceRunningInitial: Boolean,
    initialPermissions: List<BlePermission>
) : ViewModel() {
    private val permissions: MutableStateFlow<List<BlePermission>> = MutableStateFlow(emptyList())
    private val isServiceRunning: MutableStateFlow<Boolean> =
        MutableStateFlow(isServiceRunningInitial)

    val state = combine(
        availabilityTracker.bleAvailability,
        permissions,
        isServiceRunning
    ) { bleAvailability, permissions, isServiceRunning ->
        MainScreenState(
            isBluetoothEnabled = bleAvailability.isBluetoothEnabled,
            isLocationEnabled = bleAvailability.isLocationEnabled,
            permissions = permissions,
            isServiceRunning = isServiceRunning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MainScreenState(
            isBluetoothEnabled = availabilityTracker.bleAvailability.value.isBluetoothEnabled,
            isLocationEnabled = availabilityTracker.bleAvailability.value.isLocationEnabled,
            permissions = initialPermissions,
            isServiceRunning = isServiceRunningInitial
        )
    )

    fun onChangeServiceState(isRunning: Boolean) {
        isServiceRunning.value = isRunning
    }

    fun onNewPermissions(newPermissions: List<BlePermission>) {
        permissions.value = newPermissions
    }

    class Factory(
        private val availabilityTracker: BleAvailabilityObserver,
        private val isServiceRunning: Boolean,
        private val initialPermissions: List<BlePermission>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainActivityViewModel(
                availabilityTracker,
                isServiceRunning,
                initialPermissions
            ) as T
        }
    }
}