package com.sl1mslav.blescanner

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.blePermissions.collectRequiredPermissions
import com.sl1mslav.blescanner.scanner.BleScannerService
import com.sl1mslav.blescanner.scanner.model.BleDevice
import com.sl1mslav.blescanner.screens.BlePermission
import com.sl1mslav.blescanner.screens.MainScreen
import com.sl1mslav.blescanner.ui.theme.BLEscannerTheme


class MainActivity : ComponentActivity() {

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onNewPermissions(getPermissionsState())
    }

    private var scannerService: BleScannerService? = null
    private var isServiceBound: Boolean = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BleScannerService.LeScannerBinder
            scannerService = binder.getService()
            viewModel.onChangeServiceState(isRunning = true)
            val key = "2743652"
            val bleCode = "pcjhp6060px38f9b"
            val hardCodedDevice = BleDevice(
                uuid = "f45389a8-d158-4964-b8ef-00000001ab60", // todo уточнить
                keyId = 1,
                rssi = 0,
                charData = byteArrayOf(),
                key = "g$key".toByteArray(),
                bleCode = bleCode.toByteArray()
            )
            scannerService?.startScanningForDevices(listOf(hardCodedDevice), 50)
        }

        override fun onServiceDisconnected(className: ComponentName) {
            viewModel.onChangeServiceState(isRunning = false)
            scannerService = null // todo подумать нужно ли (в доке нет)
        }
    }

    private val viewModel by viewModels<MainActivityViewModel> {
        MainActivityViewModel.Factory(
            availabilityTracker = BleAvailabilityObserver.getInstance(this),
            isServiceRunning = isServiceBound,
            initialPermissions = getPermissionsState()
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
                        state = state,
                        onEnableBluetooth = {
                            openBluetoothSettings()
                        },
                        onEnableLocation = {
                            openLocationSettings()
                        },
                        onCheckPermission = ::onCheckPermission,
                        onButtonClick = {
                            if (state.isServiceRunning) {
                                stopBleScannerService()
                            } else {
                                startBleScannerService()
                            }
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
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
    }

    private fun onCheckPermission(permission: BlePermission) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                permission.manifestName
            )
        ) {
            Toast.makeText(this, "Вруби вручную, мне лень делать алерт <3", Toast.LENGTH_SHORT).show()
            return
        }

        permissionRequester.launch(permission.manifestName)
    }

    private fun getPermissionsState(): List<BlePermission> {
        return collectRequiredPermissions().map {
            BlePermission(
                manifestName = it,
                readableName = it,
                isGranted = ContextCompat.checkSelfPermission(
                    this,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
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

    companion object {
        private const val BIND_SERVICE_IF_RUNNING = 0
    }
}