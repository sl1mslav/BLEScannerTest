package com.sl1mslav.blescanner.scanner

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.sl1mslav.blescanner.notifications.buildBleServiceNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration.Companion.minutes

class BleScannerService: Service() {
    // Этот скоуп будет жить, пока жив сервис
    private val scannerRestartScope = CoroutineScope(Dispatchers.Main + Job())

    // Будит телефон с интервалом в 15 минут
    private val wakeLockWorkManager = WakeLockWorkManager(this)

    // ----- Для того, чтобы UI мог вызывать публичные методы нашего сервиса ----- //
    // todo узнать, нужно ли вообще привязывать сервис.
    private val binder = LeScannerBinder()

    inner class LeScannerBinder: Binder() {
        fun getService(): BleScannerService = this@BleScannerService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    // -------------------------------------------------------------------------- //





    // --------------------------- Инициализация сервиса -------------------------- //

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        startServiceAsFGS()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startServiceAsFGS() {
        Log.d(TAG, "startServiceAsFGS")
        ServiceCompat.startForeground(
            this,
            SERVICE_NOTIFICATION_ID,
            buildBleServiceNotification(
                context = this,
                title = "Всё включено",
                text = "Сервис запущен"
            ),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                FOREGROUND_SERVICE_TYPE_DEFAULT
            }
        )
    }
    // ---------------------------------------------------------------------------- //





    // -------------------------  Главная работа сервиса -------------------------- //

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        super.onCreate()
        startWakeLockWorker()
    }

    /**
     * Перезапускает Bluetooth сканнер, если с прошлого сигнала воркера прошло больше 5 минут
     * todo уточнить почему Рома сделал именно так (что это трекает)
     */
    @OptIn(FlowPreview::class)
    private fun startWakeLockWorker() {
        Log.d(TAG, "startWakeLockWorker")
        wakeLockWorkManager
            .start()
            .debounce(5.minutes)
            .onEach {
                Log.d(TAG, "startWakeLockWorker: receive workInfo $it")
                // todo bluetoothScanner?.restart()
            }.launchIn(scannerRestartScope) // убьётся с сервисом, не нужно проверять isAlive
    }

    // ---------------------------------------------------------------------------- //





    // ----------------------------- Очистка сервиса ------------------------------ //

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        scannerRestartScope.cancel()
        wakeLockWorkManager.stop()
    }

    // ---------------------------------------------------------------------------- //

    companion object {
        private const val TAG = "BleScannerService"
        private const val SERVICE_NOTIFICATION_ID = 1337
        private const val FOREGROUND_SERVICE_TYPE_DEFAULT = 0
    }
}