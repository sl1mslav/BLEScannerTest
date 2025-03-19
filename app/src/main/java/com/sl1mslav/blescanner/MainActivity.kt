package com.sl1mslav.blescanner

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.blePermissions.AutoStartHelper
import com.sl1mslav.blescanner.blePermissions.collectRequiredPermissions
import com.sl1mslav.blescanner.scanner.BleScanner
import com.sl1mslav.blescanner.scanner.BleScannerService
import com.sl1mslav.blescanner.scanner.model.BleDevice
import com.sl1mslav.blescanner.screens.BlePermission
import com.sl1mslav.blescanner.screens.MainScreen
import com.sl1mslav.blescanner.ui.theme.BLEscannerTheme
import kotlin.math.abs
import kotlin.math.roundToInt


class MainActivity : ComponentActivity() {

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onNewPermissions(getPermissionsState())
    }

    private val multiplePermissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onNewPermissions(getPermissionsState())
    }

    private var scannerService: BleScannerService? = null
    private var isServiceBound: Boolean = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BleScannerService.LeScannerBinder
            scannerService = binder.getService()
            val currentRssi = scannerService?.getRssi() ?: BleScanner.DEFAULT_TARGET_RSSI
            viewModel.onChangeServiceState(isRunning = true)
            val key = "2769202"
            val bleCode = "pcjhp6060px38f9b"
            val uuid = getSkudUuid(skudId = 111383)
            val hardCodedDevice = BleDevice(
                uuid = uuid,
                rssi = 0,
                charData = byteArrayOf(),
                key = "g$key".toByteArray(),
                bleCode = bleCode.toByteArray()
            )
            if (currentRssi != BleScanner.DEFAULT_TARGET_RSSI) {
                viewModel.onNewRssi(currentRssi)
            }
            scannerService?.startScanningForDevices(
                listOf(hardCodedDevice),
                viewModel.state.value.currentRssi
            )
        }

        override fun onServiceDisconnected(className: ComponentName) {
            viewModel.onChangeServiceState(isRunning = false)
            scannerService = null
        }
    }

    private val viewModel by viewModels<MainActivityViewModel> {
        MainActivityViewModel.Factory(
            availabilityTracker = BleAvailabilityObserver.getInstance(this),
            isServiceRunning = isServiceBound,
            initialPermissions = getPermissionsState(),
            ignoresDozeMode = isIgnoringDozeMode()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BLEscannerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state by viewModel.state.collectAsState()
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        state = state.copy(
                            needsAutoStart = AutoStartHelper.instance.needAutostart(this)
                        ),
                        onEnableBluetooth = {
                            openBluetoothSettings()
                        },
                        onEnableLocation = {
                            openLocationSettings()
                        },
                        onCheckPermission = ::onCheckPermission,
                        onClickScannerButton = {
                            if (state.isServiceRunning) {
                                stopBleScannerService()
                            } else {
                                startBleScannerService()
                            }
                        },
                        onCheckDozeMode = {
                            requestToIgnoreDoze()
                        },
                        onClickAutoStart = {
                            AutoStartHelper.instance.getAutoStartPermission(this)
                        },
                        onSliderValueChange = {
                            val negativeRssi = -abs(it.roundToInt())
                            scannerService?.saveNewRssi(negativeRssi)
                            viewModel.onNewRssi(negativeRssi)
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindToRunningService()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onNewPermissions(getPermissionsState())
        viewModel.onChangeDozeBehavior(isIgnoringDozeMode())
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
    }


    private fun onCheckPermission(permission: BlePermission) {
        if (permission.manifestName == ACCESS_BACKGROUND_LOCATION) {
            permissionRequester.launch(ACCESS_BACKGROUND_LOCATION)
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                permission.manifestName
            )
        ) {
            Toast.makeText(this, "Вруби вручную, мне лень делать алерт <3", Toast.LENGTH_SHORT)
                .show()
            return
        }

        permissionRequester.launch(permission.manifestName)
    }

    private fun getPermissionsState(): List<BlePermission> {
        return collectRequiredPermissions(this@MainActivity)
    }

    private fun isIgnoringDozeMode(): Boolean {
        return getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestToIgnoreDoze() {
        val intent = Intent()
        intent.action = ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
    }

    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        startActivity(intent)
    }

    private fun startBleScannerService() {
        Intent(
            this,
            BleScannerService::class.java
        ).let { intent ->
            startService(intent)
        }
        bindToRunningService()
    }

    private fun stopBleScannerService() {
        Intent(
            this,
            BleScannerService::class.java
        ).let { intent ->
            stopService(intent)
        }
    }

    private fun bindToRunningService() {
        Intent(
            this,
            BleScannerService::class.java
        ).let { intent ->
            bindService(
                intent,
                serviceConnection,
                BIND_SERVICE_IF_RUNNING
            )
        }
    }

    private fun getSkudUuid(skudId: Int): String {
        val skudIdInHex = skudId.toString(radix = HEX_RADIX)
        val skudIdPart = skudIdInHex.padStart(length = SKUD_ID_HEX_LENGTH, padChar = '0')
        return SKUD_UUID_PREFIX + skudIdPart
    }

    companion object {
        private const val BIND_SERVICE_IF_RUNNING = 0
        private const val SKUD_UUID_PREFIX = "f45389a8-d158-4964-b8ef-"
        private const val HEX_RADIX = 16
        private const val SKUD_ID_HEX_LENGTH = 12
    }
}