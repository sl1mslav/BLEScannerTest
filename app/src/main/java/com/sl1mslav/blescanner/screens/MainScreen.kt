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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sl1mslav.blescanner.ui.theme.BLEscannerTheme

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    state: MainScreenState,
    onEnableBluetooth: () -> Unit,
    onEnableLocation: () -> Unit,
    onCheckPermission: (BlePermission) -> Unit,
    onButtonClick: () -> Unit
) {
    Column(
        modifier = modifier,
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
        Button(
            onClick = onButtonClick,
            enabled = state.isServiceRunning || (state.isLocationEnabled &&
                state.isBluetoothEnabled &&
                state.permissions.all { it.isGranted })
        ) {
            val text = if (state.isServiceRunning) {
                "Stop FGS"
            } else {
                "Start FGS"
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
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.inverseSurface
        )
        Spacer(
            modifier = Modifier.weight(1f)
        )
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
            onButtonClick = {},
            state = MainScreenState(
                isBluetoothEnabled = false,
                isLocationEnabled = false,
                isServiceRunning = true,
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
                )
            ),
            onCheckPermission = {},
            onEnableBluetooth = {},
            onEnableLocation = {}
        )
    }
}