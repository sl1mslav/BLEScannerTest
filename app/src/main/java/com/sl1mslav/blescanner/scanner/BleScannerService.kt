package com.sl1mslav.blescanner.scanner

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.sl1mslav.blescanner.notifications.buildBleServiceNotification
import com.sl1mslav.blescanner.scanner.model.BleScannerError.BLUETOOTH_OFF
import com.sl1mslav.blescanner.scanner.model.BleScannerError.CONNECTION_FAILED
import com.sl1mslav.blescanner.scanner.model.BleScannerError.INCORRECT_CONFIGURATION
import com.sl1mslav.blescanner.scanner.model.BleScannerError.LOCATION_OFF
import com.sl1mslav.blescanner.scanner.model.BleScannerError.NO_CONNECT_PERMISSION
import com.sl1mslav.blescanner.scanner.model.BleScannerError.NO_SCAN_PERMISSION
import com.sl1mslav.blescanner.scanner.model.BleScannerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration.Companion.minutes

class BleScannerService: Service() {
    // Этот скоуп будет жить, пока жив сервис
    private val scannerScope = CoroutineScope(Dispatchers.Main + Job())

    // Будит телефон с интервалом в 15 минут
    private val wakeLockWorkManager = WakeLockWorkManager(this)

    // Сканнер BLE устройств
    private val bleScanner by lazy { BleScanner(context = this) }
    val bleScannerState get() = bleScanner.state

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
    // ---------------------------------------------------------------------------- //





    // -------------------------  Главная работа сервиса -------------------------- //

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        super.onCreate()
        startWakeLockWorker()
        observeBluetoothScannerState()
        bleScanner.start(emptyList()) // todo actual devices
    }

    /**
     * Перезапускает Bluetooth сканнер, если с прошлого сигнала воркера прошло больше 5 минут
     * todo попробовать потом с обычным таймером вместо отслеживания изменений воркера
     */
    @OptIn(FlowPreview::class)
    private fun startWakeLockWorker() {
        Log.d(TAG, "startWakeLockWorker")
        wakeLockWorkManager
            .start()
            .debounce(5.minutes)
            .onEach {
                Log.d(TAG, "startWakeLockWorker: receive workInfo $it")
                bleScanner.restart()
            }.launchIn(scannerScope) // убьётся с сервисом, не нужно проверять isAlive
    }

    // ---------------------------------------------------------------------------- //





    // -------------------- Реакции на состояние сканнера --------------------- //

    private fun observeBluetoothScannerState() {
        bleScannerState.onEach(::handleScannerState).launchIn(scannerScope)
    }

    private fun handleScannerState(state: BleScannerState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationTitle: String
        val notificationText: String
        when (state) {
            BleScannerState.Initial -> {
                notificationTitle = "Запускаем сканнер"
                notificationText = "Скоро он начнёт считывать сигналы"
            }
            BleScannerState.Scanning -> {
                notificationTitle = "Сканируем"
                notificationText = "Поскорее бы открыть что-нибудь"
            }
            BleScannerState.Connected -> {
                notificationTitle = "Успешно подключились"
                notificationText = "Попробуем отослать сигнал открытия"
            }
            is BleScannerState.Failed -> {
                notificationTitle = "Произошла ошибка"
                when {
                    state.errors.containsAll(listOf(BLUETOOTH_OFF, LOCATION_OFF)) -> {
                        notificationText = "Необходимо включить геолокацию и Bluetooth"
                    }
                    BLUETOOTH_OFF in state.errors -> {
                        notificationText = "Необходимо включить Bluetooth"
                    }
                    LOCATION_OFF in state.errors -> {
                        notificationText = "Необходимо включить геолокацию"
                    }
                    INCORRECT_CONFIGURATION in state.errors -> { // todo порядок
                        notificationText = "Похоже, скан был начат с неправильными данными"
                    }
                    CONNECTION_FAILED in state.errors -> {
                        notificationText = "Ошибка соединения во время скана/подключения к устройствам"
                    }
                    NO_SCAN_PERMISSION in state.errors -> {
                        notificationText = "Нет разрешения на сканирование"
                    }
                    NO_CONNECT_PERMISSION in state.errors -> {
                        notificationText = "Нет разрешения на подключение к Bluetooth"
                    }
                    else -> {
                        notificationText = "Непредвиденная ошибка"
                    }
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

    // ---------------------------------------------------------------------------- //





    // ----------------------------- Очистка сервиса ------------------------------ //

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        scannerScope.coroutineContext.cancelChildren()
        wakeLockWorkManager.stop()
        bleScanner.release()
    }

    // ---------------------------------------------------------------------------- //

    companion object {
        private const val TAG = "BleScannerService"
        private const val SERVICE_NOTIFICATION_ID = 1337
        private const val FOREGROUND_SERVICE_TYPE_DEFAULT = 0
    }
}