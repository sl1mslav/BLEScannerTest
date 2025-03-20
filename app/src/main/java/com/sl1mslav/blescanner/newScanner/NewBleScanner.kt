package com.sl1mslav.blescanner.newScanner

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_CONNECTION_CONGESTED
import android.bluetooth.BluetoothGatt.GATT_CONNECTION_TIMEOUT
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Connecting
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed.Reason
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Scanning
import com.sl1mslav.blescanner.scanner.BleScanner
import com.sl1mslav.blescanner.scanner.model.BleDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update


// GUIDE: https://punchthrough.com/android-ble-guide/
// TODO look into enqueueing ALL of the launched operations
// REPO: https://github.com/PunchThrough/ble-starter-android

class NewBleScanner(
    private val context: Context
) : ScanCallback() {

    private val state = MutableStateFlow<NewBleScannerState>(NewBleScannerState.Idle)
    private val devices = MutableStateFlow<List<BleDevice>>(emptyList())

    private var bluetoothGatt: BluetoothGatt? = null

    private val bluetoothManager by lazy {
        ContextCompat.getSystemService(
            context,
            BluetoothManager::class.java
        )
    }

    private val bluetoothLeScanner get() = bluetoothManager?.adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val scanResult = result ?: run {
                Log.d(TAG, "onScanResult: scan result is null")
                return
            }
            val foundUuid = scanResult
                .scanRecord
                ?.serviceUuids?.singleOrNull()?.uuid?.toString()
                ?: run {
                    Log.d(TAG, "onScanResult: service uuid is null")
                    return
                }
            val bluetoothDevice = scanResult.device ?: run {
                Log.d(TAG, "onScanResult: device is null")
                return
            }
            Log.d(TAG, "onScanResult: uuid = $foundUuid, rssi = ${scanResult.rssi}")

            tryConnectToDevice(
                uuid = foundUuid,
                rssi = scanResult.rssi,
                bluetoothDevice = bluetoothDevice
            )
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> {
                    Log.d(TAG, "onScanFailed: scan was already started with same settings")
                    // Reflecting this in our state just in case
                    state.update { Scanning }
                }

                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> {
                    // Bluetooth is in a bad state (32+ apps scanning) and needs a power cycle.
                    // Integrate a BluetoothMedic later?
                    Log.d(TAG, "onScanFailed: bluetooth stack is in a bad state")
                    state.update { Failed(reason = Reason.BLUETOOTH_STACK_BAD_STATE) }
                }

                SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                    Log.d(TAG, "onScanFailed: scan failed: feature not supported.")
                    // We shouldn't even get to this point. Check feature availability beforehand.
                    // https://stackoverflow.com/a/35275469/20682060
                    state.update { Failed(reason = Reason.FEATURE_NOT_SUPPORTED) }
                    // No point in restarting the scan here,
                    // only with a different bluetooth adapter maybe
                }

                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> {
                    /*
                        How did we even get here? Wasn't a safe scanner used?
                        This is bad. Repercussions vary across vendors.

                        If your app exceeded this limit, the scan would appear to have started,
                        but no scan results would be delivered to your callback body.
                        After 30 seconds have elapsed, your app should
                        call stopScan() followed by startScan(...) again
                        to start receiving scan results — the old startScan(...) method call
                        that resulted in the error doesn’t automatically start receiving
                        scan results again once the 30-second cooldown timer is complete.
                        Realistically, most apps won’t exceed this internal limit,
                        but this is an error you should watch out for. Perhaps you should warn
                        your users about whether your code or your users can potentially
                        start and stop BLE scans repeatedly.
                     */
                    state.update { Failed(reason = Reason.SCANNING_TOO_FREQUENTLY) }
                    // todo try to stop scan after 30secs and start again
                }

                SCAN_FAILED_INTERNAL_ERROR -> {
                    Log.d(TAG, "onScanFailed: internal scan error")
                    state.update { Failed(reason = Reason.SCAN_FAILED_UNKNOWN_ERROR) }
                    // We can try and restart the scan here
                    // todo restart scan
                }

                else -> {
                    Log.d(TAG, "onScanFailed: scan failed with error code $errorCode")
                    state.update { Failed(reason = Reason.SCAN_FAILED_UNKNOWN_ERROR) }
                    // We can try and restart scan here
                    // todo restart scan
                }
            }
        }
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionError(gatt = gatt, connectionStatus = status)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handleSuccessfulConnection(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "onConnectionStateChange: disconnected")
                    // todo reflect disconnection in state
                    state.update { Scanning }
                    disconnectGatt(gatt)
                    // todo restart scan
                }

                else -> {
                    // We're either connecting or disconnecting, these statuses can be ignored'
                    Log.d(TAG, "onConnectionStateChange: some other state: $newState")
                }
            }
        }

        private fun handleSuccessfulConnection(gatt: BluetoothGatt) {
            bluetoothLeScanner?.stopScan(scanCallback)
            bluetoothGatt = gatt
        }

        private fun handleConnectionError(gatt: BluetoothGatt, connectionStatus: Int) {
            Log.d(TAG, "handleConnectionError: status = $connectionStatus")
            disconnectGatt(gatt)
            if (
                connectionStatus == GATT_INSUFFICIENT_ENCRYPTION ||
                connectionStatus == GATT_INSUFFICIENT_AUTHENTICATION
            ) {
                Log.d(
                    TAG,
                    "handleConnectionError: calling createBond() and then connectGatt()"
                )
                // todo call createBond() on bluetoothDevice and wait for the bonding process to succeed
                //  before calling connectGatt() again.
                return
            }
            when (connectionStatus) {
                GATT_CONNECTION_CONGESTED -> {
                    Log.d(TAG, "handleConnectionError: connection is congested")
                    state.update { Failed(reason = Reason.CONNECTION_CONGESTED) }
                }

                GATT_CONNECTION_TIMEOUT -> {
                    Log.d(TAG, "handleConnectionError: connection timed out")
                    state.update { Failed(reason = Reason.CONNECTION_FAILED) }
                }

                GATT_FIRMWARE_ERROR -> {
                    Log.d(
                        TAG, "handleConnectionError: famous 133 error. " +
                                "Either a timeout occurred or Android refuses to connect to device."
                    )
                    state.update { Failed(reason = Reason.CONNECTION_FAILED) }
                }

                else -> {
                    // Reflecting disconnection in state
                    state.update { Scanning }
                }
            }
            // todo restart scan here
        }

        private fun disconnectGatt(gatt: BluetoothGatt) {
            bluetoothGatt = null
            try {
                gatt.close()
            } catch (e: SecurityException) {
                state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }
    }

    /**
     * Try to connect to a device.
     */
    private fun tryConnectToDevice(
        uuid: String,
        rssi: Int,
        bluetoothDevice: BluetoothDevice
    ) {
        val foundDevice = devices.value.firstOrNull { device -> device.uuid == uuid } ?: run {
            Log.d(TAG, "tryConnectToDevice: could not find device with uuid = $uuid")
            return
        }

        if (isBusy()) {
            Log.d(TAG, "tryConnectToDevice: could not connect to device: scanner is busy.")
            return
        }

        // Here I can add logic for connecting to a GATT server in advance. That would require
        // checking for rssi later, right before trying to open the device.
        // Some kind of CONNECTION_THRESHOLD constant, perhaps?
        val preferredRssi = foundDevice.rssi
        if (rssi > preferredRssi) {
            Log.d(TAG, "tryConnectToDevice: won't connect to device $uuid: signal too weak")
            return
        }

        connectDeviceGatt(
            bluetoothDevice,
            uuid,
            rssi
        )
    }

    private fun connectDeviceGatt(
        bluetoothDevice: BluetoothDevice,
        uuid: String,
        rssi: Int
    ) {
        state.update { Connecting }
        try {
            bluetoothDevice.connectGatt(
                context,
                true,
                bluetoothGattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            // todo think about reflecting connected/disconnected state
            state.update { NewBleScannerState.Connected(uuid = uuid, rssi = rssi) }
        } catch (e: SecurityException) {
            Log.e(TAG, "connectDeviceGatt: missing BLUETOOTH_CONNECT permission", e)
            state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
        }
    }

    private fun isBusy() = state is Busy

    private companion object {
        const val TAG = "NewBleScanner"

        const val GATT_FIRMWARE_ERROR = 0x85
    }
}