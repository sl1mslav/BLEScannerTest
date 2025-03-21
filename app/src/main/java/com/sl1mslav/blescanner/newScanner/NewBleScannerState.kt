package com.sl1mslav.blescanner.newScanner

sealed interface NewBleScannerState {
    data object Idle: NewBleScannerState

    data object Scanning: NewBleScannerState

    data object Connecting: NewBleScannerState, Busy

    data object Reconnecting: NewBleScannerState

    data class Connected(
        val uuid: String,
        val rssi: Int
    ): NewBleScannerState, Busy

    data class Failed(
        val reason: Reason
    ) : NewBleScannerState {
        enum class Reason {
            INCORRECT_CONFIGURATION,
            CONNECTION_FAILED,
            BLUETOOTH_OFF,
            LOCATION_OFF,
            BLUETOOTH_AND_LOCATION_OFF,
            NO_SCAN_PERMISSION,
            NO_CONNECT_PERMISSION,
            BLUETOOTH_STACK_BAD_STATE,
            FEATURE_NOT_SUPPORTED,
            SCANNING_TOO_FREQUENTLY,
            SCAN_FAILED_UNKNOWN_ERROR,
            CONNECTION_CONGESTED
        }
    }
}

sealed interface Busy