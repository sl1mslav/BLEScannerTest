package com.sl1mslav.blescanner.scanner.model

data class BleDevice( // todo naming: maybe rename to BleSkud or something
    val uuid: String,
    val rssi: Int,
    val isConnected: Boolean = false,
    val keyId: Int = 1,
    var charData : ByteArray,
    val key: ByteArray,
    val bleCode: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BleDevice

        if (uuid != other.uuid) return false
        if (rssi != other.rssi) return false
        if (isConnected != other.isConnected) return false
        if (keyId != other.keyId) return false
        if (!charData.contentEquals(other.charData)) return false
        if (!key.contentEquals(other.key)) return false
        if (!bleCode.contentEquals(other.bleCode)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + rssi
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + keyId
        result = 31 * result + charData.contentHashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + bleCode.contentHashCode()
        return result
    }
}
