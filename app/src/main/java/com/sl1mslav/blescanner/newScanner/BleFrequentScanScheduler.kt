package com.sl1mslav.blescanner.newScanner

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.jvm.Throws

class BleFrequentScanScheduler {

    private val schedulingScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val operationsLaunched = Collections.synchronizedList<Job>(mutableListOf())

    @Throws(SecurityException::class)
    fun scheduleScan(
        scanner: BluetoothLeScanner,
        filters: List<ScanFilter>,
        settings: ScanSettings,
        callback: ScanCallback
    ) {
        val job = schedulingScope.launch {
            val operationsCurrentlyLaunched = operationsLaunched.size
            if (operationsCurrentlyLaunched >= SCANS_PER_PERIOD) {
                operationsLaunched[operationsCurrentlyLaunched - SCANS_PER_PERIOD].join()
            }
            scanner.startScan(
                filters,
                settings,
                callback
            )
            delay(EXCESSIVE_SCANNING_PERIOD_SECONDS)
            operationsLaunched.removeFirstOrNull()
        }
        operationsLaunched.add(job)
    }

    private companion object {
        const val EXCESSIVE_SCANNING_PERIOD_SECONDS = 30_000L
        const val SCANS_PER_PERIOD = 5
    }
}