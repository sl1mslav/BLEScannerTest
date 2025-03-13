package com.sl1mslav.blescanner.scanner

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class WakeLockWorkManager(
    context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    private val periodicWorkRequest = PeriodicWorkRequest.Builder(
        workerClass = WakeLockWorker::class.java,
        repeatInterval = REPEAT_INTERVAL_MINUTES,
        repeatIntervalTimeUnit = TimeUnit.MINUTES
    ).build()

    fun start(): Flow<WorkInfo?> {
        Log.d(TAG, "start work")
        workManager.cancelWorkById(periodicWorkRequest.id)
        workManager.enqueue(periodicWorkRequest)
        return workManager.getWorkInfoByIdFlow(periodicWorkRequest.id)
    }

    fun stop() {
        Log.d(TAG, "stop work")
        workManager.cancelWorkById(periodicWorkRequest.id)
    }

    class WakeLockWorker(
        context: Context,
        workerParams: WorkerParameters
    ) : Worker(context, workerParams) {

        override fun doWork(): Result = try {
            val powerManager = applicationContext.getSystemService(PowerManager::class.java)
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG // LocationManagerService tag on HUAWEI not to get killed
            )
            wakeLock.acquire(WAKE_LOCK_TIMEOUT)
            Log.d(TAG, "doWork: successfuly acquired wakelock")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, e.stackTraceToString())
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "WakeLockWorker"
        const val WAKE_LOCK_TAG = "BleScanner:WakeLockTag"
        const val WAKE_LOCK_TIMEOUT = 2_000L
        const val REPEAT_INTERVAL_MINUTES = 15L
    }
}