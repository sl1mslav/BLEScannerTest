package com.sl1mslav.blescanner.scanner

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.sl1mslav.blescanner.caching.DevicesPrefsCachingService
import com.sl1mslav.blescanner.logger.Logger
import com.sl1mslav.blescanner.newScanner.BleScanner
import com.sl1mslav.blescanner.newScanner.NewBleScannerState
import com.sl1mslav.blescanner.newScanner.NewBleScannerState.Failed.Reason
import com.sl1mslav.blescanner.notifications.buildBleServiceNotification
import com.sl1mslav.blescanner.scanner.model.BleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class BleScannerService : Service() {
    // Этот скоуп будет жить, пока жив сервис
    private val scannerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Будит телефон с интервалом в 15 минут
    private val wakeLockWorkManager = WakeLockWorkManager(this)

    // Сканнер BLE устройств
    private val bleScanner by lazy { BleScanner(context = this) }
    val bleScannerState get() = bleScanner.state

    // Кэширование устройств
    private val deviceCachingService by lazy { DevicesPrefsCachingService(context = this) }

    private val binder = LeScannerBinder()

    inner class LeScannerBinder : Binder() {
        fun getService(): BleScannerService = this@BleScannerService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("onStartCommand")
        startServiceAsFGS()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        Logger.log("onCreate")
        super.onCreate()
        wakeLockWorkManager.start()
        observeBluetoothScannerState()
        scanForKnownDevices()
        launchPeriodicScanRestarts()
    }

    override fun onDestroy() {
        Logger.log("onDestroy")
        super.onDestroy()
        scannerScope.coroutineContext.cancelChildren()
        wakeLockWorkManager.stop()
        bleScanner.stop()
    }

    private fun startServiceAsFGS() {
        Logger.log("startServiceAsFGS")
        try {
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
        } catch (e: SecurityException) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(
                SERVICE_NOTIFICATION_ID,
                buildBleServiceNotification(
                    this,
                    "Проблемка...",
                    "Зачем ты вырубил разрешения? Нормально же общались?"
                )
            )
        }
    }

    fun startScanningForDevices(
        devices: List<BleDevice>
    ) {
        deviceCachingService.saveDevices(devices)
        scanForKnownDevices()
    }

    private fun launchPeriodicScanRestarts() {
        scannerScope.launch {
            while (true) {
                delay(30.minutes)
                bleScanner.restartIfNotBusy()
            }
        }
    }

    private fun scanForKnownDevices() {
        bleScanner.start()
    }

    private fun observeBluetoothScannerState() {
        bleScannerState.onEach(::handleScannerState).launchIn(scannerScope)
    }

    private fun handleScannerState(state: NewBleScannerState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationTitle: String
        val notificationText: String
        when (state) {
            NewBleScannerState.Idle -> {
                notificationTitle = "Запускаем сканнер"
                notificationText = "Скоро он начнёт считывать сигналы"
            }

            NewBleScannerState.Scanning -> {
                notificationTitle = "Сканируем"
                notificationText = "Поскорее бы открыть что-нибудь"
            }

            NewBleScannerState.Connecting -> {
                notificationTitle = "Подключаемся"
                notificationText = "Пытаемся соединиться с устройством"
            }

            NewBleScannerState.Reconnecting -> {
                notificationTitle = "Переподключаемся"
                notificationText = "Создаём пару с устройством"
            }

            is NewBleScannerState.Connected -> {
                notificationTitle = "Пытаемся открыть домофон"
                notificationText = "Текущий RSSI: ${state.rssi}"
            }

            is NewBleScannerState.Failed -> {
                notificationTitle = "Произошла ошибка"
                notificationText = when (state.reason) {
                    Reason.INCORRECT_CONFIGURATION -> "Некорректная конфигурация"
                    Reason.CONNECTION_FAILED -> "Произошёл неизвестный рофлямбус"
                    Reason.BLUETOOTH_OFF -> "Пожалуйста, включите Bluetooth"
                    Reason.LOCATION_OFF -> "Пожалуйста, включите геолокацию"
                    Reason.BLUETOOTH_AND_LOCATION_OFF -> "Пожалуйста, включите Bluetooth и геолокацию"
                    Reason.NO_SCAN_PERMISSION -> "Нет разрешения на скан"
                    Reason.NO_CONNECT_PERMISSION -> "Нет разрешения на коннект"
                    Reason.BLUETOOTH_STACK_BAD_STATE -> "Перезагрузите Bluetooth или телефон."
                    Reason.FEATURE_NOT_SUPPORTED -> "Эмм сорян но фича не поддерживается))) лал))))"
                    Reason.SCANNING_TOO_FREQUENTLY -> "Слишком часто сканим!!! Галя отмена!!!!!!!!!!"
                    Reason.SCAN_FAILED_UNKNOWN_ERROR -> "Тут даже я хз"
                    Reason.CONNECTION_CONGESTED -> "Соединение забито другими прилами"
                }
            }
        }
        notificationManager.notify(
            SERVICE_NOTIFICATION_ID,
            buildBleServiceNotification(
                context = this,
                title = notificationTitle,
                text = notificationText
            )
        )
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1337
        private const val FOREGROUND_SERVICE_TYPE_DEFAULT = 0
    }
}