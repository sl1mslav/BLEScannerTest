package com.sl1mslav.blescanner.bleAvailability

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class BleAvailabilityObserver private constructor(private val context: Context) {

    private val trackerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    private val _bleAvailability = MutableStateFlow(
        BleAvailabilityState(
            isBluetoothEnabled = bluetoothManager.adapter.isEnabled,
            isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
        )
    )

    /**
     * Состояние вкл/выкл блютуза и геолокации
     * Запускает BroadcastReceiver для прослушивания системных статусов,
     * отключает их как только пропадает последний подписчик.
     */
    val bleAvailability = _bleAvailability.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                _bleAvailability.update {
                    it.copy(
                        isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
                    )
                }
            }
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                _bleAvailability.update {
                    it.copy(
                        isBluetoothEnabled = bluetoothManager.adapter.isEnabled
                    )
                }
            }
        }
    }

    init {
        _bleAvailability.subscriptionCount
            .map { count -> count > 0 }
            .distinctUntilChanged()
            .onEach { isActive ->
                if (isActive) {
                    startSystemBroadcastListener()
                } else {
                    stopSystemBroadcastListener()
                }
            }.launchIn(trackerScope)
    }

    private fun startSystemBroadcastListener() {
        val intentFilter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun stopSystemBroadcastListener() {
        if (receiver.debugUnregister)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {}
    }


    companion object {
        // it's fine because we're using application context
        @SuppressLint("StaticFieldLeak")
        private var instance: BleAvailabilityObserver? = null

        fun getInstance(context: Context): BleAvailabilityObserver {
            return instance ?: BleAvailabilityObserver(context.applicationContext)
        }
    }
}