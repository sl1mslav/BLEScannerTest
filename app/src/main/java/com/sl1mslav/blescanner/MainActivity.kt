package com.sl1mslav.blescanner

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver
import com.sl1mslav.blescanner.scanner.BleScannerService
import com.sl1mslav.blescanner.ui.theme.BLEscannerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var scannerService: BleScannerService? = null
    private var isServiceBound: Boolean = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BleScannerService.LeScannerBinder
            scannerService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(className: ComponentName) {
            isServiceBound = false
            scannerService = null // todo подумать нужно ли (в доке нет)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BLEscannerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onButtonClick = {
                            startBleScannerService()
                        }
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BleAvailabilityObserver
                    .getInstance(this@MainActivity)
                    .bleAvailability
                    .collect {
                        Log.d("TAG", it.toString())
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindToRunningService()
    }

    override fun onStop() {
        super.onStop()
        unbindService()
    }

    private fun startBleScannerService() {
        Intent(
            this,
            BleScannerService::class.java
        ).let { intent ->
            startService(intent)
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

    private fun unbindService() {
        unbindService(serviceConnection)
        isServiceBound = false
    }

    companion object {
        private const val BIND_SERVICE_IF_RUNNING = 0
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onButtonClick: () -> Unit
) {
    Button(
        modifier = modifier,
        onClick = onButtonClick
    ) {
        Text(text = "Start FGS")
    }
}

@Preview
@Composable
fun MainScreenPreview() {
    MainScreen(onButtonClick = {})
}