package com.example.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.core.model.DeliveryStatus
import com.example.core.model.TransportType

@Entity(tableName = "crypto_identities")
data class CryptoIdentityEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CryptoIdentityEntity
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

@Entity(
    tableName = "peers",
    indices = [
        Index(value = ["deviceId"], unique = true),
        Index(value = ["lastSeen"]),
        Index(value = ["connectionState"])
    ]
)
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val publicKeyBytes: ByteArray? = null,
    val fingerprint: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
    val transport: TransportType = TransportType.AUTO,
    val connectionState: String = "DISCOVERED", // DISCOVERED, CONNECTED, DISCONNECTED
    val signalStrength: Int = -60, // RSSI or dBm
    val protocolVersion: Int = 1,
    val isVerified: Boolean = false,
    val canRelay: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerEntity
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["conversationId"], unique = true),
        Index(value = ["peerDeviceId"]),
        Index(value = ["updatedAt"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val type: String = "DIRECT", // DIRECT, GROUP
    val lastMessage: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["conversationId"]),
        Index(value = ["senderDeviceId"]),
        Index(value = ["recipientDeviceId"]),
        Index(value = ["createdAt"]),
        Index(value = ["state"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val plaintext: String,
    val encryptedPayload: ByteArray? = null,
    val nonce: ByteArray? = null,
    val authTag: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86400000L,
    val state: DeliveryStatus = DeliveryStatus.QUEUED,
    val hopCount: Int = 0,
    val ttl: Int = 10,
    val isOutgoing: Boolean = true,
    val relayHops: String = "" // comma-separated hops e.g. "B, C"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageEntity
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}

@Entity(tableName = "message_seen")
data class MessageSeenEntity(
    @PrimaryKey val messageId: String,
    val seenAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "routing_table",
    indices = [
        Index(value = ["destinationDeviceId"], unique = true)
    ]
)
data class RoutingEntity(
    @PrimaryKey val destinationDeviceId: String,
    val nextHopDeviceId: String,
    val hopCount: Int,
    val transport: TransportType,
    val updatedAt: Long = System.currentTimeMillis()
)
