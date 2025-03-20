package com.sl1mslav.blescanner.newScanner

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_CONNECTION_CONGESTED
import android.bluetooth.BluetoothGatt.GATT_CONNECTION_TIMEOUT
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.core.content.ContextCompat
import com.sl1mslav.blescanner.encryption.encryptDeviceCommand
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Connecting
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed.Reason
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Scanning
import com.sl1mslav.blescanner.scanner.model.BleDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID


// GUIDE: https://punchthrough.com/android-ble-guide/
// TODO look into enqueueing ALL of the launched operations
// REPO: https://github.com/PunchThrough/ble-starter-android

class NewBleScanner(
    private val context: Context
) : ScanCallback() {

    private val state = MutableStateFlow<NewBleScannerState>(NewBleScannerState.Idle)
    private val devices = MutableStateFlow<List<BleDevice>>(emptyList())

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothCharacteristic: BluetoothGattCharacteristic? = null
    private var bluetoothCharacteristicNotification: BluetoothGattCharacteristic? = null

    private val bluetoothManager by lazy {
        ContextCompat.getSystemService(
            context,
            BluetoothManager::class.java
        )
    }

    private val bluetoothLeScanner get() = bluetoothManager?.adapter?.bluetoothLeScanner

    private fun getBondStateReceiverForDevice(
        uuid: String,
        initialRssi: Int,
        device: BluetoothDevice
    ) = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val receivedDevice = intent.parcelableExtraCompat<BluetoothDevice>(
                    key = BluetoothDevice.EXTRA_DEVICE
                )
                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                )
                if (
                    receivedDevice != null &&
                    receivedDevice.address == device.address &&
                    bondState == BluetoothDevice.BOND_BONDED
                ) {
                    Log.d(TAG, "bondStateReceiver: bond successful, let's connectGatt again")
                    context.unregisterReceiver(this)
                    tryConnectToDevice(
                        uuid = uuid,
                        rssi = initialRssi,
                        bluetoothDevice = receivedDevice
                    )
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Log.d(TAG, "bondStateReceiver: stopped bonding. Let's unregister this now")
                    context.unregisterReceiver(this)
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val scanResult = result ?: run {
                Log.d(TAG, "onScanResult: scan result is null")
                return
            }
            val foundUuid = scanResult
                .scanRecord
                ?.serviceUuids?.firstOrNull()?.uuid?.toString()
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

    private inner class DeviceBluetoothGattCallback(
        private val uuid: String,
        private val initialRssi: Int
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionError(
                    gatt = gatt,
                    connectionStatus = status
                )
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handleSuccessfulConnection(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "onConnectionStateChange: disconnected")
                    state.update { Scanning }
                    disconnectGatt(gatt)
                    // todo restart scan
                }

                else -> {
                    // We're either connecting or disconnecting, these statuses can be ignored
                    Log.d(TAG, "onConnectionStateChange: some other state: $newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleDiscoveredServices(gatt)
            } else {
                Log.d(
                    TAG,
                    "onServicesDiscovered: service discovery failed due to status $status"
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Using other version of this callback on older Androids
                return
            }

            // Read the encryption code
            updateCurrentDevice(gatt) { device ->
                device.copy(charData = value.copyOfRange(fromIndex = 1, toIndex = value.size))
            }

            try {
                Log.d(TAG, "onCharacteristicChanged: reading remote rssi")
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                Log.d(
                    TAG,
                    "onCharacteristicChanged: could not read remote rssi: no CONNECT permission"
                )
                state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }

        @Deprecated("This still has to be used to support Android < 14")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            super.onCharacteristicChanged(gatt, characteristic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Using other version of this callback on newer Androids
                return
            }

            @Suppress("DEPRECATION")
            val value = characteristic.value

            // Read the encryption code
            updateCurrentDevice(gatt) { device ->
                device.copy(charData = value.copyOfRange(fromIndex = 1, toIndex = value.size))
            }

            try {
                Log.d(TAG, "onCharacteristicChanged: reading remote rssi")
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                Log.d(
                    TAG,
                    "onCharacteristicChanged: could not read remote rssi: no CONNECT permission"
                )
                state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "onCharacteristicWrite: successfully opened door")
                    // todo relay info about door being open...?
                }

                BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                    Log.d(TAG, "onCharacteristicWrite: write operation not permitted!")
                }

                else -> {
                    Log.d(TAG, "onCharacteristicWrite: unknown error. Status = $status")
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            Log.d(TAG, "onReadRemoteRssi: newRssi: $rssi")
            state.update { NewBleScannerState.Connected(uuid, rssi) }
            currentDeviceFromGatt(gatt)?.let { device ->
                Log.d(TAG, "onReadRemoteRssi: sending open signal")
                sendOpenSignal(gatt, device)
            } ?: run {
                Log.d(TAG, "onReadRemoteRssi: couldn't send open signal: device is null")
            }
        }

        private fun handleSuccessfulConnection(gatt: BluetoothGatt) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDING) {
                    bluetoothGatt = gatt
                    state.update { NewBleScannerState.Connected(uuid, initialRssi) }
                    // Using a Handler here to avoid a nasty bug on older androids
                    // And ensure that services are discovered on the main thread
                    Handler(Looper.getMainLooper()).post {
                        val couldDiscoverServices = gatt.discoverServices()
                        if (!couldDiscoverServices) {
                            Log.d(
                                TAG,
                                "handleSuccessfulConnection: could not start services discovery"
                            )
                        }
                    }
                } else {
                    // Bonding is in progress, wait for it to finish
                    Log.d(
                        TAG,
                        "handleSuccessfulConnection: waiting for bonding to complete"
                    )
                }
            } catch (e: SecurityException) {
                state.update {
                    Failed(reason = Reason.NO_SCAN_PERMISSION)
                }
            }
        }

        private fun handleConnectionError(
            gatt: BluetoothGatt,
            connectionStatus: Int
        ) {
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
                tryToAuthorizeDevice(gatt)
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

        private fun tryToAuthorizeDevice(
            gatt: BluetoothGatt
        ) {
            val device = gatt.device ?: run {
                Log.d(TAG, "tryToAuthorizeDevice: device is null")
                state.update { Failed(reason = Reason.CONNECTION_FAILED) }
                return
            }
            val bondStateReceiver = getBondStateReceiverForDevice(
                uuid,
                initialRssi,
                device
            )
            ContextCompat.registerReceiver(
                context,
                bondStateReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            try {
                Log.d(TAG, "tryToAuthorizeDevice: beginning bonding process")
                val hasBondingStarted = device.createBond()
                if (!hasBondingStarted) {
                    Log.d(TAG, "tryToAuthorizeDevice: could not start bonding process")
                    context.unregisterReceiver(bondStateReceiver)
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "tryToAuthorizeDevice: no connect permission, can't create bond")
                context.unregisterReceiver(bondStateReceiver)
                state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }

        private fun handleDiscoveredServices(gatt: BluetoothGatt) {
            val characteristics = gatt.services?.flatMap { service ->
                service?.characteristics?.filterNotNull() ?: emptyList()
            } ?: run {
                Log.d(TAG, "handleDiscoveredServices: services are null")
                return
            }
            parseAndSaveCharacteristics(characteristics)
            enableNotifications(gatt)
        }

        private fun parseAndSaveCharacteristics(characteristics: List<BluetoothGattCharacteristic>) {
            characteristics.forEach { characteristic ->
                // When we find a characteristic for notifications, we save it to subscribe to its
                // updates
                if (characteristic.uuid == UUID.fromString(BLE_DEFAULT_CHARACTERISTIC_UUID)) {
                    bluetoothCharacteristic = characteristic
                }

                // When we find a characteristic for sending open signals, we save it for later use
                if (characteristic.uuid == UUID.fromString(BLE_DEFAULT_NOTIFICATION_UUID)) {
                    bluetoothCharacteristicNotification = characteristic
                }
            }
        }

        private fun enableNotifications(gatt: BluetoothGatt) {
            // Subscribe to characteristic's updates
            if (bluetoothCharacteristicNotification == null) {
                Log.d(TAG, "enableNotifications: couldn't enable notifications as char is null")
                return
            }
            try {
                gatt.setCharacteristicNotification(
                    bluetoothCharacteristicNotification,
                    true
                )
                bluetoothCharacteristicNotification?.descriptors?.firstOrNull()?.let { descriptor ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        bluetoothGatt?.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        @Suppress("DEPRECATION")
                        bluetoothGatt?.writeDescriptor(descriptor)
                    }
                }
            } catch (e: SecurityException) {
                state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }
    }

    private fun sendOpenSignal(
        gatt: BluetoothGatt,
        device: BleDevice
    ) {
        Log.d(TAG, "sendOpenSignal: checking characteristic for null")
        val characteristic = bluetoothCharacteristic ?: return
        Log.d(TAG, "sendOpenSignal: check passed")
        val command = encryptDeviceCommand(bleDevice = device)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                Log.d(TAG, "sendOpenSignal: result status = $result")
                if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                    Log.d(TAG, "sendOpenSignal: device is busy! Restarting scanner.")
                    // todo restart scanner???
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                if (!gatt.writeCharacteristic(characteristic)) {
                    Log.d(TAG, "sendOpenSignal: could not write characteristic on Android < 14")
                }

            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendOpenSignal: missing permission", e)
        }
    }

    private fun currentDeviceFromGatt(
        gatt: BluetoothGatt
    ): BleDevice? {
        val uuids = gatt.services.map { service -> service.uuid.toString() }
        return devices.value.firstOrNull { device -> device.uuid in uuids }
    }

    private fun updateCurrentDevice(
        gatt: BluetoothGatt,
        transform: (BleDevice) -> BleDevice
    ) {
        val currentDevice = currentDeviceFromGatt(gatt) ?: return
        val updatedDevice = transform(currentDevice)
        devices.update { devices ->
            devices - currentDevice + updatedDevice
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
                DeviceBluetoothGattCallback(
                    uuid = uuid,
                    initialRssi = rssi
                ),
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "connectDeviceGatt: missing BLUETOOTH_CONNECT permission", e)
            state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
        }
    }

    private fun isBusy() = state is Busy

    /**
     * A backwards compatible approach of obtaining a parcelable extra from an [Intent] object.
     *
     * NOTE: Despite the docs stating that [Intent.getParcelableExtra] is deprecated in Android 13,
     * Google has confirmed in https://issuetracker.google.com/issues/240585930#comment6 that the
     * replacement API is buggy for Android 13, and they suggested that developers continue to use the
     * deprecated API for Android 13. The issue will be fixed for Android 14 (U).
     */
    internal inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? =
        when {
            Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(
                key,
                T::class.java
            )

            else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
        }

    private companion object {
        const val TAG = "NewBleScanner"

        const val GATT_FIRMWARE_ERROR = 0x85

        const val BLE_DEFAULT_CHARACTERISTIC_UUID = "0000f401-0000-1000-8000-00805f9b34fb"
        const val BLE_DEFAULT_NOTIFICATION_UUID = "0000f402-0000-1000-8000-00805f9b34fb"
    }
}