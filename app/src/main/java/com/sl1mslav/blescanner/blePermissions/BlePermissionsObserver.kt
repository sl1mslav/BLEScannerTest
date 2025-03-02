package com.sl1mslav.blescanner.blePermissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
typealias Permission = String

class BlePermissionsObserver(
    private val context: Context
): DefaultLifecycleObserver {
    private val _permissionsState = MutableStateFlow(
        calculatePermissionsState()
    )
    val permissionsState = _permissionsState.asStateFlow()

    override fun onResume(owner: LifecycleOwner) {

    }

    private fun calculatePermissionsState(): BlePermissionsState { // todo продумать разницу в версиях
        return BlePermissionsState(
            areNotificationsGranted = Manifest.permission.POST_NOTIFICATIONS.isGranted(),
            areBluetoothPermissionsGranted = false,
            isBackgroundLocationGranted = false
        )
    }

    private fun Permission.isGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            this
        ) == PackageManager.PERMISSION_GRANTED
    }
}