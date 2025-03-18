package com.sl1mslav.blescanner.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sl1mslav.blescanner.ui.theme.BLEscannerTheme

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    state: MainScreenState,
    onEnableBluetooth: () -> Unit,
    onEnableLocation: () -> Unit,
    onCheckPermission: (BlePermission) -> Unit,
    onCheckDozeMode: () -> Unit,
    onClickAutoStart: () -> Unit,
    onClickScannerButton: () -> Unit,
    onSliderValueChange: (Float) -> Unit
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .defaultMinSize(minHeight = 45.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Bluetooth ${if (state.isBluetoothEnabled) "включён" else "выключен"}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.inverseSurface
            )
            Spacer(
                modifier = Modifier.weight(1f)
            )
            if (!state.isBluetoothEnabled) {
                TextButton(onClick = onEnableBluetooth) {
                    Text(text = "Включить")
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .defaultMinSize(minHeight = 45.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Локация ${if (state.isLocationEnabled) "включена" else "выключена"}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.inverseSurface
            )
            Spacer(
                modifier = Modifier.weight(1f)
            )
            if (!state.isLocationEnabled) {
                TextButton(onClick = onEnableLocation) {
                    Text(text = "Включить")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        state.permissions.forEach { blePermission ->
            LabelSwitch(
                label = blePermission.readableName,
                isChecked = blePermission.isGranted,
                onCheckedChange = { isChecked ->
                    if (isChecked) {
                        onCheckPermission(blePermission)
                    }
                }
            )
        }
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            text = "Опциональное | Людское",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        LabelSwitch(
            label = "Игнор оптимизации заряда",
            isChecked = state.ignoresDozeMode,
            onCheckedChange = { isChecked ->
                if (isChecked) {
                    onCheckDozeMode()
                }
            }
        )
        if (state.needsAutoStart) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .defaultMinSize(minHeight = 45.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Автозагрузка приложения\n(не можем знать, вкл или выкл)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.inverseSurface
                )
                TextButton(onClick = onClickAutoStart) {
                    Text(text = "Включить")
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = "RSSI:   - ${state.currentRssi} dBm")
        Spacer(modifier = Modifier.height(16.dp))
        Slider(
            modifier = Modifier.padding(horizontal = 32.dp),
            value = state.currentRssi.toFloat(),
            valueRange = 0f..100f,
            steps = 100,
            onValueChange = onSliderValueChange
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onClickScannerButton,
            enabled = state.isServiceRunning || (state.isLocationEnabled &&
                    state.isBluetoothEnabled &&
                    state.permissions.all { it.isGranted })
        ) {
            val text = if (state.isServiceRunning) {
                "Остановить сканнер"
            } else {
                "Запустить сканнер"
            }
            Text(text = text)
        }
    }
}

@Preview
@Composable
private fun LabelSwitch(
    label: String = "Название разрешения, например",
    isChecked: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.inverseSurface
        )
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            colors = SwitchDefaults.colors(
                uncheckedBorderColor = Color.Transparent
            ),
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}

@SuppressLint("InlinedApi")
@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun MainScreenPreview() {
    BLEscannerTheme {
        MainScreen(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            onClickScannerButton = {},
            state = MainScreenState(
                isBluetoothEnabled = false,
                isLocationEnabled = false,
                isServiceRunning = false,
                permissions = listOf(
                    BlePermission(
                        manifestName = Manifest.permission.POST_NOTIFICATIONS,
                        readableName = "Уведомления",
                        isGranted = true
                    ),
                    BlePermission(
                        manifestName = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        readableName = "Локация в фоне",
                        isGranted = false
                    )
                ),
                ignoresDozeMode = false,
                needsAutoStart = true,
                currentRssi = 50
            ),
            onCheckPermission = {},
            onEnableBluetooth = {},
            onEnableLocation = {},
            onCheckDozeMode = {},
            onClickAutoStart = {},
            onSliderValueChange = {}
        )
    }
}