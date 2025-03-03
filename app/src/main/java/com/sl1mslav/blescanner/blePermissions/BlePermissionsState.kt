package com.sl1mslav.blescanner.blePermissions

data class BlePermissionsState(
    val notifications: PermissionStatus,
    val bluetooth: PermissionStatus,
    val backgroundLocation: PermissionStatus // todo сделать отдельные пермишны на каждые??
)

enum class PermissionStatus {
    GRANTED,
    NOT_GRANTED,
    NOT_NEEDED
}