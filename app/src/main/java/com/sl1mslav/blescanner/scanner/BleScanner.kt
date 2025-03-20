package com.sl1mslav.blescanner.scanner

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.encryption.encryptDeviceCommand
import com.sl1mslav.blescanner.scanner.model.BleDevice
import com.sl1mslav.blescanner.scanner.model.BleScannerError
import com.sl1mslav.blescanner.scanner.model.BleScannerState
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
import kotlin.math.abs

class BleScanner(
    private val context: Context
) {
    private enum class ConnectionState {
        IN_PROGRESS,
        CONNECTED,
        NOT_CONNECTED
    }

    var targetRssi = DEFAULT_TARGET_RSSI
        set(value) {
            field = -abs(value) // guarantee negative value when set
        }

    private val scannerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bleAvailability = BleAvailabilityObserver
        .getInstance(context)
        .bleAvailability

    private val bluetoothManager = ContextCompat.getSystemService(
        context,
        BluetoothManager::class.java
    )

    private val bluetoothLeScanner = bluetoothManager?.adapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothCharacteristic: BluetoothGattCharacteristic? = null
    private var bluetoothCharacteristicNotification: BluetoothGattCharacteristic? = null

    private val _devices = MutableStateFlow(emptyList<BleDevice>())

    private val _state = MutableStateFlow<BleScannerState>(BleScannerState.Initial)
    val state = _state.asStateFlow()

    private var connectionState = ConnectionState.NOT_CONNECTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val serviceUuids = result?.scanRecord?.serviceUuids
            Log.d(TAG, "onScanResult: $serviceUuids")
            serviceUuids?.forEach {
                if (
                    connectionState == ConnectionState.NOT_CONNECTED &&
                    result.rssi < CONNECTION_TRESHHOLD_RSSI
                ) {
                    connectToDevice(
                        uuid = it.uuid.toString(),
                        bluetoothDevice = result.device
                    )
                    return@forEach
                } else {
                    Log.d(TAG, "onScanResult: Устройство $it найдено, сигнал недостаточный")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                _state.update { it.failed(withError = BleScannerError.CONNECTION_FAILED) }
                try {
                    bluetoothGatt?.close()
                    startScanningForDevices(_devices.value)
                } catch (e: SecurityException) {
                    _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
                }
            }
        }
    }

    private val bleGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            // Обрабатываем неуспешное подключение
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onConnectionStateChange: произошла ошибка, статус $status") // todo resolve status 8

                updateCurrentBleDevice(gatt) {
                    it.copy(isConnected = false)
                }

                try {
                    bluetoothGatt?.close()
                    gatt.close()
                } catch (e: SecurityException) {
                    _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
                } finally {
                    restart()
                }
                return
            }

            // На этом этапе понимаем, что успешно подключились
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        val bondState = gatt.device.bondState
                        if (bondState != BluetoothDevice.BOND_BONDING) {
                            bluetoothGatt = gatt
                            connectionState = ConnectionState.CONNECTED
                            _state.update { BleScannerState.Connected }
                            scannerScope.launch {
                                delay(DEFAULT_DISCOVERY_DELAY)
                                val couldDiscoverServices = gatt.discoverServices()
                                if (!couldDiscoverServices) {
                                    Log.d(
                                        TAG,
                                        "onConnectionStateChange: could not start services discovery"
                                    )
                                }
                            }
                        } else {
                            // Bonding в процессе, ждём пока закончится
                            Log.d(
                                TAG,
                                "onConnectionStateChange: waiting for bonding to complete"
                            )
                        }
                    } catch (e: SecurityException) {
                        _state.update {
                            it.failed(withError = BleScannerError.NO_SCAN_PERMISSION)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onConnectionStateChange: проблема с подключением", e)
                        _state.update {
                            it.failed(withError = BleScannerError.CONNECTION_FAILED)
                        }
                        startScanningForDevices(_devices.value)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "onConnectionStateChange: disconnected")
                    // Мы успешно отключились (контролируемое отключение)
                    updateCurrentBleDevice(gatt) {
                        it.copy(isConnected = false)
                    }

                    try {
                        restart()
                        gatt.close()
                    } catch (e: SecurityException) {
                        _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
                    }
                }

                else -> {
                    // Мы или подключаемся или отключаемся, просто игнорируем эти статусы
                    Log.d(TAG, "onConnectionStateChange: some other state: $newState")
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            Log.d(TAG, "onReadRemoteRssi: newRssi: $rssi")
            updateCurrentBleDevice(gatt) {
                val newDevice = it.copy(rssi = rssi)
                Log.d(TAG, "onReadRemoteRssi: set new device rssi: $rssi")
                Log.d(TAG, "onReadRemoteRssi: new device connected: ${newDevice.isConnected}")
                if (newDevice.rssi >= targetRssi && newDevice.isConnected) {
                    sendOpenSignal(newDevice)
                }
                newDevice
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            // Разбираем сервисы и характиристика подключенного устройства
            parseServices(gatt)

            val currentBleDevice = bleDeviceFromGatt(gatt)
            Log.d(TAG, "onServicesDiscovered: подключились к ${currentBleDevice?.uuid}")

            // Включение подписки на обновление характиристики
            try {
                bluetoothGatt?.setCharacteristicNotification(
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
                _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                return
            Log.d(TAG, "onCharacteristicChanged: ${characteristic.uuid}, Android >= 14")
            // Читаем код для шифрования
            if (characteristic.uuid.toString().startsWith(CHARACTERISTIC_PREFIX)) {
                updateCurrentBleDevice(gatt) {
                    it.copy(charData = value.copyOfRange(fromIndex = 1, toIndex = value.size))
                }
            }
            try {
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU)
                return
            Log.d(TAG, "onCharacteristicChanged: ${characteristic.uuid}, Android < 14")
            @Suppress("DEPRECATION")
            val value = characteristic.value
            // Читаем код для шифрования
            if (characteristic.uuid.toString().startsWith(CHARACTERISTIC_PREFIX)) {
                updateCurrentBleDevice(gatt) {
                    it.copy(charData = value.copyOfRange(fromIndex = 1, toIndex = value.size))
                }
            }
            try {
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
            }
        }
    }

    fun start(bleDevices: List<BleDevice>) {
        if (bleDevices.isEmpty()) {
            Log.d(TAG, "startScanningForDevices: skipping scan: given devices list is empty")
            return
        }

        if (
            bleDevices.map { it.uuid }.sortedDescending() ==
            _devices.value.map { it.uuid }.sortedDescending()
        ) {
            Log.d(TAG, "startScanningForDevices: skipping scan: same devices list")
            return
        }

        _devices.update { bleDevices }
        observeBleAvailability()
        startScanningForDevices(bleDevices)
        Log.d(TAG, "startScanningForDevices: start scanning for devices: ${bleDevices.joinToString { it.uuid }}")
    }

    fun release() {
        stopScanning()
        scannerScope.coroutineContext.cancelChildren()
    }

    fun restart() {
        Log.d(TAG, "restart: restarting scanner")
        connectionState = ConnectionState.NOT_CONNECTED
        _devices.update { devices -> devices.map { device -> device.copy(isConnected = false) } }
        _state.update { BleScannerState.Initial }
        scannerScope.launch {
            delay(500L)
            startScanningForDevices(_devices.value)
        }
    }

    private tailrec fun startScanningForDevices(bleDevices: List<BleDevice>) {
        if (connectionState != ConnectionState.NOT_CONNECTED || _state.value == BleScannerState.Connected) {
            Log.d(TAG, "startScanningForDevices: can't start scan; connection is in progress")
            return
        }
        val shouldTryAgain = try {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            bluetoothLeScanner?.startScan(
                createSearchFiltersForScanning(devices = bleDevices),
                scanSettings,
                scanCallback
            )
            _state.update { BleScannerState.Scanning }
            false
        } catch (e: SecurityException) {
            _state.update { it.failed(withError = BleScannerError.NO_SCAN_PERMISSION) }
            false
        } catch (e: IllegalArgumentException) {
            _state.update { it.failed(withError = BleScannerError.INCORRECT_CONFIGURATION) }
            false
        } catch (e: Exception) {
            Log.e(TAG, "startScanningForDevices: ошибка, попытаемся запустить сканнер", e)
            true
        }
        if (shouldTryAgain) {
            startScanningForDevices(bleDevices)
        }
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

    private fun stopScanning() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "stopScanning: missing permission", e)
            _state.update { state ->
                state.failed(withError = BleScannerError.NO_SCAN_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopScanning: couldn't stop scan", e)
        }

        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "stopScanning: missing permission", e)
            _state.update { state ->
                state.failed(withError = BleScannerError.NO_CONNECT_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopScanning: couldn't close GATT client", e)
        }
    }

    private fun observeBleAvailability() {
        bleAvailability.onEach { state ->
            when {
                !state.isBluetoothEnabled && !state.isLocationEnabled -> {
                    _state.update {
                        BleScannerState.Failed(
                            errors = setOf(
                                BleScannerError.BLUETOOTH_OFF,
                                BleScannerError.LOCATION_OFF
                            )
                        )
                    }
                    stopScanning()
                }

                !state.isBluetoothEnabled -> {
                    _state.update {
                        it.failed(withError = BleScannerError.BLUETOOTH_OFF)
                    }
                    stopScanning()
                }

                !state.isLocationEnabled -> {
                    _state.update {
                        it.failed(withError = BleScannerError.LOCATION_OFF)
                    }
                    stopScanning()
                }

                else -> {
                    // поскольку это StateFlow, понимаем что данные точно поменялись
                    // и спокойно рестартим сканнер, раз всё включено
                    restart()
                }
            }
        }.launchIn(scannerScope)
    }

    private fun connectToDevice(
        uuid: String,
        bluetoothDevice: BluetoothDevice
    ) {
        connectionState = ConnectionState.IN_PROGRESS
        try {
            bluetoothGatt = bluetoothDevice.connectGatt(
                context,
                true,
                bleGattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            _devices.update { devices ->
                devices.map { device ->
                    if (device.uuid == uuid)
                        device.copy(isConnected = true)
                    else
                        device
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "connectToDevice: Отсутствует разрешение BLUETOOTH_CONNECT", e)
            _state.update { it.failed(withError = BleScannerError.NO_CONNECT_PERMISSION) }
        }
    }

    private fun sendOpenSignal(device: BleDevice) {
        Log.d(TAG, "sendOpenSignal: checking characteristic and gatt for null")
        val characteristic = bluetoothCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        Log.d(TAG, "sendOpenSignal: check passed")
        val command = encryptDeviceCommand(bleDevice = device)

        try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            when (result) {
                BluetoothStatusCodes.SUCCESS -> {
                    Log.d(TAG, "sendOpenSignal: Успешное открытие двери")
                    // todo передавать инфу о том что объект открылся...?
                }

                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                    Log.d(TAG, "sendOpenSignal: Устройство занято, перезапускаем сканнер")
                    restart()
                }

                BluetoothStatusCodes.ERROR_UNKNOWN -> {
                    Log.d(TAG, "sendOpenSignal: Неизвестная ошибка")
                }

                else -> {
                    Log.d(TAG, "sendOpenSignal: Неизвестная проблема. Статус: $result")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendOpenSignal: missing permission", e)
        }
    }

    private fun parseServices(gatt: BluetoothGatt) {
        gatt.services?.forEach { gattService ->
            gattService.characteristics.forEach { char ->
                // При нахождени характеристики для уведомления, сохраняем ее для подписывания на изменения в ней
                if (char.uuid == UUID.fromString(BLE_DEFAULT_CHARACTERISTIC_UUID)) {
                    bluetoothCharacteristic = char
                }

                // При нахождении нужной характеристики, сохраняем ее для последующих записей в нее
                if (char.uuid == UUID.fromString(BLE_DEFAULT_NOTIFICATION_UUID)) {
                    bluetoothCharacteristicNotification = char
                }
            }
        }
    }

    private fun updateCurrentBleDevice(
        gatt: BluetoothGatt,
        transformCurrentDevice: (BleDevice) -> BleDevice
    ) {
        val currentBleDevice = bleDeviceFromGatt(gatt)
        _devices.update { devices ->
            val device = devices.firstOrNull { it.uuid == currentBleDevice?.uuid }
            if (device != null) {
                devices - device + transformCurrentDevice(device)
            } else {
                devices
            }
        }
    }

    private fun bleDeviceFromGatt(gatt: BluetoothGatt?): BleDevice? {
        val serviceUuids = gatt?.services?.map { it.uuid.toString().uppercase() }
        return _devices.value.firstOrNull { bleDevice ->
            serviceUuids?.contains(bleDevice.uuid.uppercase()) == true
        }
    }

    companion object {
        const val DEFAULT_TARGET_RSSI = -50
        private const val TAG = "BleScanner"
        private const val CONNECTION_TRESHHOLD_RSSI = -75
        private const val DEFAULT_DISCOVERY_DELAY = 2L

        // Маска для uuid чтобы  искать только 16 бит uuid
        private const val SERVICE_UUID_MASK: String = "FFFFFFFF-FFFF-FFFF-FFFF-ffffffffffff"
        private val PARCEL_UUID_MASK = ParcelUuid.fromString(SERVICE_UUID_MASK)

        private const val CHARACTERISTIC_PREFIX = "0000f402"
        private const val BLE_DEFAULT_CHARACTERISTIC_UUID = "0000f401-0000-1000-8000-00805f9b34fb"
        private const val BLE_DEFAULT_NOTIFICATION_UUID = "0000f402-0000-1000-8000-00805f9b34fb"
    }
}