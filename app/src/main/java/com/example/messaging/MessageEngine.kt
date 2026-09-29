package com.example.messaging

import android.content.Context
import com.example.core.crypto.CryptoIdentity
import com.example.core.crypto.CryptoManager
import com.example.core.model.DeliveryStatus
import com.example.core.model.MeshPacket
import com.example.core.model.PacketType
import com.example.database.ConversationEntity
import com.example.database.CryptoIdentityEntity
import com.example.database.MessageEntity
import com.example.database.MeshDatabase
import com.example.database.PeerEntity
import com.example.routing.MeshRouter
import com.example.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID

class MessageEngine(
    val context: Context,
    val database: MeshDatabase,
    val transportManager: TransportManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _currentIdentity = MutableStateFlow<CryptoIdentityEntity?>(null)
    val currentIdentity: StateFlow<CryptoIdentityEntity?> = _currentIdentity.asStateFlow()

    private var localKeyPair: KeyPair? = null
    var router: MeshRouter? = null

    init {
        scope.launch {
            loadOrCreateIdentity()
        }
    }

    suspend fun loadOrCreateIdentity(customDisplayName: String? = null): CryptoIdentityEntity {
        var existing = database.identityDao().getIdentity()
        if (existing == null) {
            val keyPair = CryptoManager.generateEcKeyPair()
            val pubBytes = keyPair.public.encoded
            val privBytes = keyPair.private.encoded
            val fingerprint = CryptoManager.computeFingerprint(pubBytes)
            val generatedId = "node_${UUID.randomUUID().toString().take(8)}"
            val name = customDisplayName ?: "Mesh Node ${generatedId.takeLast(4)}"

            existing = CryptoIdentityEntity(
                deviceId = generatedId,
                displayName = name,
                publicKeyBytes = pubBytes,
                privateKeyBytes = privBytes,
                fingerprint = fingerprint
            )
            database.identityDao().setIdentity(existing)
            localKeyPair = keyPair
        } else {
            val pub = CryptoManager.parsePublicKey(existing.publicKeyBytes)
            val priv = CryptoManager.parsePrivateKey(existing.privateKeyBytes)
            localKeyPair = KeyPair(pub, priv)
        }

        _currentIdentity.value = existing

        if (router == null) {
            val r = MeshRouter(
                myDeviceId = existing.deviceId,
                database = database,
                transportManager = transportManager,
                scope = scope
            )
            router = r
            listenForIncomingMessages(r)
        }
        return existing
    }

    private fun listenForIncomingMessages(r: MeshRouter) {
        scope.launch {
            transportManager.incomingPackets.collect { (packet, _) ->
                if (packet.packetType == PacketType.MESSAGE &&
                    (packet.recipientDeviceId == _currentIdentity.value?.deviceId || packet.recipientDeviceId == "*")
                ) {
                    processIncomingMessagePacket(packet)
                }
            }
        }
    }

    private suspend fun processIncomingMessagePacket(packet: MeshPacket) {
        // Look up sender's peer record to get their public key for ECDH
        val senderPeer = database.peerDao().getPeerById(packet.senderDeviceId)
        val decryptedText = try {
            if (senderPeer?.publicKeyBytes != null && localKeyPair != null) {
                val theirPub = CryptoManager.parsePublicKey(senderPeer.publicKeyBytes)
                val sharedSecret = CryptoManager.computeSharedSecret(localKeyPair!!.private, theirPub)
                val decryptedBytes = CryptoManager.decryptAesGcm(
                    ciphertext = packet.encryptedPayload,
                    nonce = packet.nonce,
                    authTag = packet.authTag,
                    symmetricKey = sharedSecret
                )
                String(decryptedBytes, Charsets.UTF_8)
            } else {
                // Fallback for broadcast or direct test payloads
                String(packet.encryptedPayload, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // If sender key is not yet exchanged or tamper detected, attempt string decode or flag
            String(packet.encryptedPayload, Charsets.UTF_8).ifBlank { "[Encrypted Mesh Payload]" }
        }

        val convId = packet.conversationId.ifBlank { "conv_${packet.senderDeviceId}" }
        val senderName = senderPeer?.displayName ?: "Node ${packet.senderDeviceId.takeLast(4)}"

        // Ensure conversation exists
        database.conversationDao().upsertConversation(
            ConversationEntity(
                conversationId = convId,
                peerDeviceId = packet.senderDeviceId,
                peerDisplayName = senderName,
                lastMessage = decryptedText,
                updatedAt = packet.createdAt
            )
        )

        // Insert incoming message
        val msgEntity = MessageEntity(
            messageId = packet.messageId.ifBlank { UUID.randomUUID().toString() },
            conversationId = convId,
            senderDeviceId = packet.senderDeviceId,
            recipientDeviceId = packet.recipientDeviceId,
            plaintext = decryptedText,
            encryptedPayload = packet.encryptedPayload,
            nonce = packet.nonce,
            authTag = packet.authTag,
            createdAt = packet.createdAt,
            state = DeliveryStatus.DELIVERED,
            hopCount = packet.hopCount,
            ttl = packet.ttl,
            isOutgoing = false
        )
        database.messageDao().insertMessage(msgEntity)
    }

    suspend fun sendMessage(conversationId: String, peerDeviceId: String, text: String): MessageEntity {
        val myId = _currentIdentity.value?.deviceId ?: "local_node"
        val messageId = "msg_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()

        // 1. Immediately persist locally in QUEUED state
        val message = MessageEntity(
            messageId = messageId,
            conversationId = conversationId,
            senderDeviceId = myId,
            recipientDeviceId = peerDeviceId,
            plaintext = text,
            createdAt = now,
            state = DeliveryStatus.QUEUED,
            isOutgoing = true
        )
        database.messageDao().insertMessage(message)
        database.conversationDao().updateLastMessage(conversationId, text, now)

        // 2. Encrypt payload
        val peer = database.peerDao().getPeerById(peerDeviceId)
        val encryptedResult = if (peer?.publicKeyBytes != null && localKeyPair != null) {
            val theirPub = CryptoManager.parsePublicKey(peer.publicKeyBytes)
            val sharedSecret = CryptoManager.computeSharedSecret(localKeyPair!!.private, theirPub)
            CryptoManager.encryptAesGcm(text.toByteArray(Charsets.UTF_8), sharedSecret)
        } else {
            // When public key not yet available, wrap payload bytes
            val raw = text.toByteArray(Charsets.UTF_8)
            com.example.core.crypto.EncryptedResult(
                ciphertext = raw,
                nonce = ByteArray(12),
                authTag = ByteArray(16)
            )
        }

        // 3. Hand off to MeshRouter for store-and-forward transmission
        router?.routeOutboxMessage(
            message = message,
            encryptedBytes = encryptedResult.ciphertext,
            nonce = encryptedResult.nonce,
            authTag = encryptedResult.authTag
        )

        return message
    }

    fun getConversationMessages(conversationId: String): Flow<List<MessageEntity>> {
        return database.messageDao().getMessagesForConversation(conversationId)
    }

    fun getAllConversations(): Flow<List<ConversationEntity>> {
        return database.conversationDao().getAllConversations()
    }

    suspend fun updateDisplayName(name: String) {
        val current = _currentIdentity.value ?: return
        val updated = current.copy(displayName = name)
        database.identityDao().setIdentity(updated)
        _currentIdentity.value = updated
    }

    suspend fun clearAllData() {
        database.clearAllTables()
        loadOrCreateIdentity()
    }
}
