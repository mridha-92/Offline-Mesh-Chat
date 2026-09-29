package com.example.core.crypto

data class CryptoIdentity(
    val deviceId: String,
    val displayName: String,
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray,
    val fingerprint: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CryptoIdentity
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}
