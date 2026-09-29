package com.example.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedResult(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val authTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedResult
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

object CryptoManager {
    private const val EC_CURVE = "secp256r1"
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_NONCE_LENGTH_BYTES = 12
    private val secureRandom = SecureRandom()

    fun generateEcKeyPair(): KeyPair {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(EC_CURVE)
        keyPairGen.initialize(ecSpec, secureRandom)
        return keyPairGen.generateKeyPair()
    }

    fun computeSharedSecret(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(theirPublicKey, true)
        val rawSecret = keyAgreement.generateSecret()
        // Derive 256-bit symmetric key using SHA-256 digest
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(rawSecret)
    }

    fun encryptAesGcm(plaintext: ByteArray, symmetricKey: ByteArray): EncryptedResult {
        val nonce = ByteArray(GCM_NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)

        val secretKey = SecretKeySpec(symmetricKey, "AES")
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val fullOutput = cipher.doFinal(plaintext)
        // In Java AES/GCM, fullOutput contains ciphertext + 16 bytes tag at the end
        val tagLengthBytes = GCM_TAG_LENGTH_BITS / 8
        val cipherLength = fullOutput.size - tagLengthBytes

        val ciphertext = fullOutput.copyOfRange(0, cipherLength)
        val authTag = fullOutput.copyOfRange(cipherLength, fullOutput.size)

        return EncryptedResult(
            ciphertext = ciphertext,
            nonce = nonce,
            authTag = authTag
        )
    }

    fun decryptAesGcm(
        ciphertext: ByteArray,
        nonce: ByteArray,
        authTag: ByteArray,
        symmetricKey: ByteArray
    ): ByteArray {
        val combined = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)

        val secretKey = SecretKeySpec(symmetricKey, "AES")
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(combined)
    }

    fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hash = sha256.digest(publicKeyBytes)
        // Format as readable safety number in blocks e.g. "AB12-34CD-EF56-7890"
        val hex = hash.joinToString("") { "%02X".format(it) }
        return hex.take(16).chunked(4).joinToString("-")
    }

    fun parsePublicKey(bytes: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(bytes))
    }

    fun parsePrivateKey(bytes: ByteArray): PrivateKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(bytes))
    }
}
