package com.sl1mslav.blescanner.blePermissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private typealias Permission = String

class BlePermissionsObserver(
    private val context: Context
): DefaultLifecycleObserver {
    private val _permissionsState = MutableStateFlow(
        calculatePermissionsState()
    )
    val permissionsState = _permissionsState.asStateFlow()

    override fun onResume(owner: LifecycleOwner) {
        _permissionsState.update { calculatePermissionsState() }
    }

    private fun calculatePermissionsState(): BlePermissionsState { // todo продумать разницу в версиях
        val notificationsStatus = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (Manifest.permission.POST_NOTIFICATIONS.isGranted())
                    PermissionStatus.GRANTED
                else
                    PermissionStatus.NOT_GRANTED
            }
            else -> {
                PermissionStatus.NOT_NEEDED
            }
        }

    }

    private fun Permission.isGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            this
        ) == PackageManager.PERMISSION_GRANTED
    }
}