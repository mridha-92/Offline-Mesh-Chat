package com.example.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Versioned binary network packet envelope for opportunistic store-and-forward mesh routing.
 */
data class MeshPacket(
    val protocolVersion: Byte = 1,
    val packetType: PacketType = PacketType.MESSAGE,
    val packetId: String = UUID.randomUUID().toString(),
    val messageId: String = "",
    val conversationId: String = "",
    val senderDeviceId: String,
    val recipientDeviceId: String, // Recipient device ID or "*" for broadcast
    val hopCount: Int = 0,
    val ttl: Int = 10,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86400000L, // 24 hours default
    val ackType: AckType? = null,
    val nonce: ByteArray = ByteArray(0),
    val encryptedPayload: ByteArray = ByteArray(0),
    val authTag: ByteArray = ByteArray(0)
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt || ttl <= 0

    fun withDecrementedTtl(): MeshPacket = copy(
        ttl = ttl - 1,
        hopCount = hopCount + 1
    )

    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeByte(protocolVersion.toInt())
            out.writeByte(packetType.code.toInt())
            out.writeUTF(packetId)
            out.writeUTF(messageId)
            out.writeUTF(conversationId)
            out.writeUTF(senderDeviceId)
            out.writeUTF(recipientDeviceId)
            out.writeInt(hopCount)
            out.writeInt(ttl)
            out.writeLong(createdAt)
            out.writeLong(expiresAt)
            out.writeUTF(ackType?.name ?: "")

            // Nonce
            out.writeInt(nonce.size)
            if (nonce.isNotEmpty()) out.write(nonce)

            // Encrypted payload
            out.writeInt(encryptedPayload.size)
            if (encryptedPayload.isNotEmpty()) out.write(encryptedPayload)

            // Auth tag
            out.writeInt(authTag.size)
            if (authTag.isNotEmpty()) out.write(authTag)

            out.flush()
        }
        return baos.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket
        return packetId == other.packetId
    }

    override fun hashCode(): Int = packetId.hashCode()

    companion object {
        fun fromByteArray(bytes: ByteArray): MeshPacket {
            val bais = ByteArrayInputStream(bytes)
            DataInputStream(bais).use { inStream ->
                val protocolVer = inStream.readByte()
                val typeCode = inStream.readByte()
                val pktId = inStream.readUTF()
                val msgId = inStream.readUTF()
                val convId = inStream.readUTF()
                val senderId = inStream.readUTF()
                val recipientId = inStream.readUTF()
                val hopCount = inStream.readInt()
                val ttl = inStream.readInt()
                val createdAt = inStream.readLong()
                val expiresAt = inStream.readLong()
                val ackTypeStr = inStream.readUTF()
                val ack = if (ackTypeStr.isNotEmpty()) runCatching { AckType.valueOf(ackTypeStr) }.getOrNull() else null

                val nonceLen = inStream.readInt()
                val nonce = ByteArray(nonceLen)
                if (nonceLen > 0) inStream.readFully(nonce)

                val payloadLen = inStream.readInt()
                val payload = ByteArray(payloadLen)
                if (payloadLen > 0) inStream.readFully(payload)

                val tagLen = inStream.readInt()
                val authTag = ByteArray(tagLen)
                if (tagLen > 0) inStream.readFully(authTag)

                return MeshPacket(
                    protocolVersion = protocolVer,
                    packetType = PacketType.fromCode(typeCode),
                    packetId = pktId,
                    messageId = msgId,
                    conversationId = convId,
                    senderDeviceId = senderId,
                    recipientDeviceId = recipientId,
                    hopCount = hopCount,
                    ttl = ttl,
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                    ackType = ack,
                    nonce = nonce,
                    encryptedPayload = payload,
                    authTag = authTag
                )
            }
        }
    }
}
