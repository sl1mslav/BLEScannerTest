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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import androidx.core.content.ContextCompat
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.caching.DevicesPrefsCachingService
import com.sl1mslav.blescanner.encryption.encryptDeviceCommand
import com.sl1mslav.blescanner.logger.Logger
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Connecting
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed.Reason
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Scanning
import com.sl1mslav.blescanner.scanner.model.BleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID


// GUIDE: https://punchthrough.com/android-ble-guide/
// TODO look into enqueueing ALL of the launched operations
// REPO: https://github.com/PunchThrough/ble-starter-android

class NewBleScanner(
    private val context: Context
) : ScanCallback() {

    private val scannerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<NewBleScannerState>(NewBleScannerState.Idle)
    val state = _state.asStateFlow()

    private val devices = MutableStateFlow<List<BleDevice>>(emptyList())

    private val scanScheduler = BleFrequentScanScheduler(
        onMissingScanPermission = {
            Logger.log("error while scheduling scan - no SCAN permission")
            _state.update { Failed(reason = Reason.NO_SCAN_PERMISSION) }
        }
    )

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothCharacteristic: BluetoothGattCharacteristic? = null
    private var bluetoothCharacteristicNotification: BluetoothGattCharacteristic? = null

    private val deviceCachingService = DevicesPrefsCachingService(context)

    private val bleAvailability = BleAvailabilityObserver
        .getInstance(context)
        .bleAvailability // todo this restarts scan even on first collection! think about it.

    private val bluetoothManager by lazy {
        ContextCompat.getSystemService(
            context,
            BluetoothManager::class.java
        )
    }

    private val bluetoothLeScanner
        get() = bluetoothManager
            ?.adapter
            ?.bluetoothLeScanner

    /**
     * Starts scanning for cached devices.
     * The scan is skipped if no devices are cached, or if
     * scanning for same devices is already in effect.
     */
    fun start() {
        val cachedDevices = deviceCachingService.getSavedDevices()
        if (cachedDevices.isEmpty()) {
            Logger.log("skipping scan - there are no cached devices")
            return
        }

        if (
            devices.value.map { device -> device.uuid }.sortedDescending() ==
            cachedDevices.map { device -> device.uuid }.sortedDescending()
        ) {
            Logger.log("skipping scan - scanning for same devices is in progress")
            return
        }

        Logger.log("start scanning for devices ${cachedDevices.joinToString { it.uuid }}")
        devices.update { cachedDevices }

        observeBleAvailability()
        startScanningForDevices(cachedDevices)
    }

    fun stop() {
        stopScanning()
        scannerScope.coroutineContext.cancelChildren()
    }

    private fun observeBleAvailability() {
        bleAvailability.onEach { bleState ->
            when {
                !bleState.isBluetoothEnabled && !bleState.isLocationEnabled -> {
                    _state.update {
                        Failed(
                            reason = Reason.BLUETOOTH_AND_LOCATION_OFF
                        )
                    }
                    stopScanning()
                }

                !bleState.isBluetoothEnabled -> {
                    _state.update {
                        Failed(reason = Reason.BLUETOOTH_OFF)
                    }
                    stopScanning()
                }

                !bleState.isLocationEnabled -> {
                    _state.update {
                        Failed(reason = Reason.LOCATION_OFF)
                    }
                    stopScanning()
                }

                else -> {
                    // Because we're collecting a StateFlow, we understand that data
                    // has definitely changed since last value;
                    // Since everything is ON now, we safely restart the scan.
                    restartScan()
                }
            }
        }.launchIn(scannerScope)
    }

    private tailrec fun startScanningForDevices(devices: List<BleDevice>) {
        if (isBusy()) {
            Logger.log("can't start scan; connection is in progress")
            return
        }
        val shouldTryAgain = try {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            Logger.log("scheduling scan")
            bluetoothLeScanner?.let { scanner ->
                scanScheduler.scheduleScan(
                    scanner = scanner,
                    filters = createSearchFiltersForScanning(devices),
                    settings = scanSettings,
                    callback = scanCallback
                )
            }
            _state.update { Scanning }
            false
        } catch (e: SecurityException) {
            Logger.log("can't start scan: missing SCAN permission")
            _state.update { Failed(reason = Reason.NO_SCAN_PERMISSION) }
            false
        } catch (e: IllegalArgumentException) {
            Logger.log(
                "can't start scan: somehow the settings can't be built",
                e = e
            )
            _state.update { Failed(reason = Reason.INCORRECT_CONFIGURATION) }
            false
        } catch (e: Exception) {
            Log.e(TAG, "error, retrying scan", e)
            true
        }
        if (shouldTryAgain) {
            startScanningForDevices(devices)
        }
    }

    private fun stopScanning() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Logger.log("missing SCAN permission")
            _state.update { Failed(reason = Reason.NO_SCAN_PERMISSION) }
        } catch (e: Exception) {
            Log.e(TAG, "couldn't stop scan", e)
        }

        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {
            Log.e(TAG, "missing CONNECT permission", e)
            _state.update {
                Failed(reason = Reason.NO_CONNECT_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "couldn't close GATT client", e)
        }
    }

    private fun restartScan() {
        Logger.log("restarting scanner")
        _state.update { NewBleScannerState.Idle }
        startScanningForDevices(devices.value)
    }

    private fun createSearchFiltersForScanning(
        devices: List<BleDevice>
    ): List<ScanFilter> {
        return devices.map { device ->
            ScanFilter.Builder().setServiceUuid(
                ParcelUuid.fromString(device.uuid),
                PARCEL_UUID_MASK
            ).build()
        }
    }

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
                    Logger.log("bond successful, let's connectGatt again")
                    context.unregisterReceiver(this)
                    tryConnectToDevice(
                        uuid = uuid,
                        rssi = initialRssi,
                        bluetoothDevice = receivedDevice
                    )
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Logger.log("stopped bonding. Let's unregister this now")
                    context.unregisterReceiver(this)
                    _state.update { Scanning }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val scanResult = result ?: run {
                Logger.log("scan result is null")
                return
            }
            val foundUuid = scanResult
                .scanRecord
                ?.serviceUuids?.firstOrNull()?.uuid?.toString()
                ?: run {
                    Logger.log("service uuid is null")
                    return
                }
            val bluetoothDevice = scanResult.device ?: run {
                Logger.log("device is null")
                return
            }
            Logger.log("uuid = $foundUuid, rssi = ${scanResult.rssi}")

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
                    Logger.log("scan was already started with same settings")
                    // Reflecting this in our state just in case
                    _state.update { Scanning }
                }

                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> {
                    // Bluetooth is in a bad state (32+ apps scanning) and needs a power cycle.
                    // Integrate a BluetoothMedic later?
                    Logger.log("bluetooth stack is in a bad state")
                    _state.update { Failed(reason = Reason.BLUETOOTH_STACK_BAD_STATE) }
                }

                SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                    Logger.log("scan failed: feature not supported.")
                    // We shouldn't even get to this point. Check feature availability beforehand.
                    // https://stackoverflow.com/a/35275469/20682060
                    _state.update { Failed(reason = Reason.FEATURE_NOT_SUPPORTED) }
                    // No point in restarting the scan here,
                    // only with a different bluetooth adapter maybe
                }

                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> {
                    /*
                        Пизда. Как мы вообще сюда попали?

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
                    Logger.log("scanning too frequently!")
                    _state.update { Failed(reason = Reason.SCANNING_TOO_FREQUENTLY) }
                    stopScanning()
                    scannerScope.coroutineContext.cancelChildren()
                    scannerScope.launch {
                        Logger.log("let's wait out the cooldown...")
                        delay(TOO_FREQUENT_SCAN_COOLDOWN)
                        Logger.log("cooldown passed, restarting the scan")
                        observeBleAvailability()
                        restartScan()
                    }
                }

                SCAN_FAILED_INTERNAL_ERROR -> {
                    Logger.log("internal scan error")
                    _state.update { Failed(reason = Reason.SCAN_FAILED_UNKNOWN_ERROR) }
                    // We can try and restart the scan here
                    restartScan()
                }

                else -> {
                    Logger.log("scan failed with error code $errorCode")
                    _state.update { Failed(reason = Reason.SCAN_FAILED_UNKNOWN_ERROR) }
                    // We can try and restart scan here
                    restartScan()
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
                    Logger.log("disconnected")
                    _state.update { Scanning }
                    disconnectGatt(gatt)
                    restartScan()
                }

                else -> {
                    // We're either connecting or disconnecting, these statuses can be ignored
                    Logger.log("some other state: $newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleDiscoveredServices(gatt)
            } else {
                Logger.log(
                    "service discovery failed due to status $status"
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
                Logger.log("reading remote rssi")
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                Logger.log(
                    "could not read remote rssi: no CONNECT permission",
                    e = e
                )
                _state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
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
                Logger.log("reading remote rssi")
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                Logger.log(
                    "could not read remote rssi: no CONNECT permission",
                    e
                )
                _state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
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
                    Logger.log("successfully opened door")
                    // todo relay info about door being open...?
                }

                BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                    Logger.log("write operation not permitted!")
                }

                else -> {
                    Logger.log("unknown error. Status = $status")
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            Logger.log("newRssi: $rssi")
            _state.update { NewBleScannerState.Connected(uuid, rssi) }
            currentDeviceFromGatt(gatt)?.let { device ->
                Logger.log("sending open signal")
                if (rssi > device.preferredRssi) {
                    sendOpenSignal(gatt, device)
                }
            } ?: run {
                Logger.log("couldn't send open signal: device is null")
            }
        }

        private fun handleSuccessfulConnection(gatt: BluetoothGatt) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDING) {
                    bluetoothGatt = gatt
                    _state.update { NewBleScannerState.Connected(uuid, initialRssi) }
                    // Using a Handler here to avoid a nasty bug on older androids
                    // And ensure that services are discovered on the main thread
                    Handler(Looper.getMainLooper()).post {
                        val couldDiscoverServices = gatt.discoverServices()
                        if (!couldDiscoverServices) {
                            Logger.log(
                                "could not start services discovery"
                            )
                        }
                    }
                } else {
                    // Bonding is in progress, wait for it to finish
                    Logger.log(
                        "waiting for bonding to complete"
                    )
                }
            } catch (e: SecurityException) {
                _state.update {
                    Failed(reason = Reason.NO_SCAN_PERMISSION)
                }
            }
        }

        private fun handleConnectionError(
            gatt: BluetoothGatt,
            connectionStatus: Int
        ) {
            Logger.log("status = $connectionStatus")
            disconnectGatt(gatt)
            if (
                connectionStatus == GATT_INSUFFICIENT_ENCRYPTION ||
                connectionStatus == GATT_INSUFFICIENT_AUTHENTICATION
            ) {
                Logger.log(
                    "calling createBond() and then connectGatt()"
                )
                tryToAuthorizeDevice(gatt)
                return
            }
            when (connectionStatus) {
                GATT_CONNECTION_CONGESTED -> {
                    Logger.log("connection is congested")
                    _state.update { Failed(reason = Reason.CONNECTION_CONGESTED) }
                }

                GATT_CONNECTION_TIMEOUT -> {
                    Logger.log("connection timed out")
                    _state.update { Failed(reason = Reason.CONNECTION_FAILED) }
                }

                GATT_FIRMWARE_ERROR -> {
                    Logger.log(
                        "infamous 133 error. " +
                                "Either a timeout occurred or Android refuses to connect to device."
                    )
                    _state.update { Failed(reason = Reason.CONNECTION_FAILED) }
                }
            }
            restartScan()
        }

        private fun disconnectGatt(gatt: BluetoothGatt) {
            bluetoothGatt = null
            try {
                gatt.close()
            } catch (e: SecurityException) {
                _state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }

        private fun tryToAuthorizeDevice(
            gatt: BluetoothGatt
        ) {
            val device = gatt.device ?: run {
                Logger.log("device is null")
                _state.update { Failed(reason = Reason.CONNECTION_FAILED) }
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
                Logger.log("beginning bonding process")
                _state.update { NewBleScannerState.Reconnecting }
                val hasBondingStarted = device.createBond()
                if (!hasBondingStarted) {
                    Logger.log("could not start bonding process")
                    context.unregisterReceiver(bondStateReceiver)
                    restartScan()
                }
            } catch (e: SecurityException) {
                Logger.log("no connect permission, can't create bond")
                context.unregisterReceiver(bondStateReceiver)
                _state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }

        private fun handleDiscoveredServices(gatt: BluetoothGatt) {
            val characteristics = gatt.services?.flatMap { service ->
                service?.characteristics?.filterNotNull() ?: emptyList()
            } ?: run {
                Logger.log("handleDiscoveredServices: services are null")
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
                Logger.log("couldn't enable notifications as char is null")
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
                _state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
            }
        }
    }

    private fun sendOpenSignal(
        gatt: BluetoothGatt,
        device: BleDevice
    ) {
        Logger.log("checking characteristic for null")
        val characteristic = bluetoothCharacteristic ?: return
        Logger.log("check passed")
        val command = encryptDeviceCommand(bleDevice = device)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                Logger.log("result status = $result")
                if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                    Logger.log("device is busy! Restarting scanner.")
                    restartScan()
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                if (!gatt.writeCharacteristic(characteristic)) {
                    Logger.log("could not write characteristic on Android < 14")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "missing permission", e)
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
            Logger.log("could not find device with uuid = $uuid")
            return
        }

        if (isBusy()) {
            Logger.log("could not connect to device: scanner is busy.")
            return
        }

        // Here I can add logic for connecting to a GATT server in advance. That would require
        // checking for rssi later, right before trying to open the device.
        // Some kind of CONNECTION_THRESHOLD constant, perhaps?
        if (rssi < foundDevice.preferredRssi) {
            Logger.log("won't connect to device $uuid: signal too weak")
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
        _state.update { Connecting }
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
            Log.e(TAG, "missing BLUETOOTH_CONNECT permission", e)
            _state.update { Failed(reason = Reason.NO_CONNECT_PERMISSION) }
        }
    }

    private fun isBusy() = _state.value is Busy

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

        // UUID mask to only search for 16 bit UUIDs
        const val SERVICE_UUID_MASK: String = "FFFFFFFF-FFFF-FFFF-FFFF-ffffffffffff"
        val PARCEL_UUID_MASK: ParcelUuid = ParcelUuid.fromString(SERVICE_UUID_MASK)

        const val TOO_FREQUENT_SCAN_COOLDOWN = 30_000L
    }
}