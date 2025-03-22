package com.sl1mslav.blescanner.newScanner

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import com.sl1mslav.blescanner.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections

class BleFrequentScanScheduler(
    private val onMissingScanPermission: () -> Unit
) {

    private val schedulingScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val operationsLaunched = Collections.synchronizedList<Job>(mutableListOf())

    /**
     * Schedules a BLE scan. If there were less than 5 scans run in the last 30 seconds,
     * runs the scan instantly. If there 5+ scans tried in the last 30 seconds, schedules a scan
     * to run right after a time slot is freed for scanning.
     */
    fun scheduleScan(
        scanner: BluetoothLeScanner,
        filters: List<ScanFilter>,
        settings: ScanSettings,
        callback: ScanCallback
    ) {
        val operationsCurrentlyLaunched = operationsLaunched.size
        val job = schedulingScope.launch {
            if (operationsCurrentlyLaunched >= SCANS_PER_PERIOD) {
                Logger.log("over the limit!")
                operationsLaunched[operationsCurrentlyLaunched - SCANS_PER_PERIOD].join()
            }
            try {
                Logger.log("starting scan!")
                scanner.startScan(
                    filters,
                    settings,
                    callback
                )
            } catch (e: SecurityException) {
                onMissingScanPermission()
            }
            delay(EXCESSIVE_SCANNING_PERIOD_MS)
            operationsLaunched.removeFirstOrNull()
        }
        operationsLaunched.add(job)
    }

    private companion object {
        const val EXCESSIVE_SCANNING_PERIOD_MS = 30_000L
        const val SCANS_PER_PERIOD = 5
    }
}