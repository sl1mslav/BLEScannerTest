package com.sl1mslav.blescanner.scanner.model

sealed interface BleScannerState {
    data object Initial: BleScannerState
    data object Scanning: BleScannerState
    data object Connected: BleScannerState
    data class Failed(val errors: Set<BleScannerError>): BleScannerState

    fun failed(withError: BleScannerError): Failed {
        return if (this is Failed) {
            this.copy(errors = errors + withError)
        } else {
            Failed(errors = setOf(withError))
        }
    }
}

enum class BleScannerError {
    INCORRECT_CONFIGURATION,
    CONNECTION_FAILED,
    BLUETOOTH_OFF,
    LOCATION_OFF,
    NO_SCAN_PERMISSION,
    NO_CONNECT_PERMISSION
}