package com.sl1mslav.blescanner.encryption

import android.annotation.SuppressLint
import com.sl1mslav.blescanner.scanner.model.BleDevice
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM_NAME = "AES"
private const val ENCRYPTION_MODE = "$ALGORITHM_NAME/ECB/NoPadding"
private const val CODE_BYTE_ARRAY_SIZE = 16

fun encryptDeviceCommand(bleDevice: BleDevice): ByteArray {
    val code = (bleDevice.charData + bleDevice.key).copyOf(newSize = CODE_BYTE_ARRAY_SIZE)
    val command = encryptAes128(
        plaintext = code,
        key = SecretKeySpec(
            bleDevice.bleCode,
            ALGORITHM_NAME
        )
    )
    return command
}

@SuppressLint("GetInstance")
private fun encryptAes128(plaintext: ByteArray, key: SecretKey): ByteArray {
    val cipher = Cipher.getInstance(ENCRYPTION_MODE)
    val keySpec = SecretKeySpec(key.encoded, ALGORITHM_NAME)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    val cipherText = cipher.doFinal(plaintext)
    return cipherText
}