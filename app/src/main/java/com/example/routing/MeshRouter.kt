package com.example.routing

import com.example.core.model.AckType
import com.example.core.model.DeliveryStatus
import com.example.core.model.MeshPacket
import com.example.core.model.PacketType
import com.example.core.model.TransportType
import com.example.database.MessageEntity
import com.example.database.MessageSeenEntity
import com.example.database.MeshDatabase
import com.example.database.RoutingEntity
import com.example.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class RelayLog(
    val packetId: String,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val hopCount: Int,
    val ttl: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class MeshRouter(
    val myDeviceId: String,
    private val database: MeshDatabase,
    private val transportManager: TransportManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    var isRelayEnabled: Boolean = true

    private val memorySeenCache = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val _relayLogs = MutableSharedFlow<RelayLog>(extraBufferCapacity = 64)
    val relayLogs = _relayLogs.asSharedFlow()

    init {
        // Listen to incoming packets from all transports
        scope.launch {
            transportManager.incomingPackets.collect { (packet, transport) ->
                handleIncomingPacket(packet, transport)
            }
        }
    }

    suspend fun isPacketSeen(packetId: String): Boolean {
        if (memorySeenCache.contains(packetId)) return true
        val seenCount = database.seenDao().hasSeen(packetId)
        return seenCount > 0
    }

    suspend fun markPacketSeen(packetId: String) {
        memorySeenCache.add(packetId)
        database.seenDao().markSeen(MessageSeenEntity(messageId = packetId))
    }

    suspend fun handleIncomingPacket(packet: MeshPacket, ingressTransport: TransportType) {
        // 1. Deduplication check
        if (isPacketSeen(packet.packetId)) {
            return // Drop duplicate
        }
        markPacketSeen(packet.packetId)

        // 2. TTL check
        if (packet.isExpired()) {
            return // Dropped expired
        }

        // 3. Check destination
        if (packet.recipientDeviceId == myDeviceId || packet.recipientDeviceId == "*") {
            // Reached destination!
            when (packet.packetType) {
                PacketType.MESSAGE -> {
                    // Update delivery status and send DELIVERED ACK back to sender
                    sendAck(
                        targetDeviceId = packet.senderDeviceId,
                        originalMessageId = packet.messageId,
                        originalPacketId = packet.packetId,
                        ackType = AckType.DELIVERED
                    )
                }
                PacketType.ACK -> {
                    handleAckPacket(packet)
                }
                else -> {}
            }
        } else {
            // Intermediate relay node
            if (isRelayEnabled) {
                forwardPacket(packet, ingressTransport)
            }
        }
    }

    suspend fun forwardPacket(packet: MeshPacket, ingressTransport: TransportType) {
        val forwardedPacket = packet.withDecrementedTtl()
        if (forwardedPacket.isExpired()) return

        _relayLogs.emit(
            RelayLog(
                packetId = packet.packetId,
                senderDeviceId = packet.senderDeviceId,
                recipientDeviceId = packet.recipientDeviceId,
                hopCount = forwardedPacket.hopCount,
                ttl = forwardedPacket.ttl
            )
        )

        // Forward ACK to sender that intermediate relay received and forwarded it
        sendAck(
            targetDeviceId = packet.senderDeviceId,
            originalMessageId = packet.messageId,
            originalPacketId = packet.packetId,
            ackType = AckType.RELAYED
        )

        // Forward packet to known next hop or broadcast to available peers
        val nextRoute = database.routingDao().getRoute(packet.recipientDeviceId)
        if (nextRoute != null) {
            transportManager.sendPacket(forwardedPacket, nextRoute.nextHopDeviceId, nextRoute.transport)
        } else {
            // Flood to connected peers except sender
            val peers = transportManager.allDiscoveredPeers.value
            for (peer in peers) {
                if (peer.deviceId != packet.senderDeviceId) {
                    transportManager.sendPacket(forwardedPacket, peer.deviceId)
                }
            }
        }
    }

    private suspend fun handleAckPacket(packet: MeshPacket) {
        val ack = packet.ackType ?: return
        val messageId = packet.messageId
        val currentMsg = database.messageDao().getMessageById(messageId) ?: return

        when (ack) {
            AckType.RECEIVED -> {
                if (currentMsg.state == DeliveryStatus.QUEUED || currentMsg.state == DeliveryStatus.SENDING) {
                    database.messageDao().updateMessageState(messageId, DeliveryStatus.ROUTING)
                }
            }
            AckType.RELAYED -> {
                val updatedHops = if (currentMsg.relayHops.isBlank()) "Relayed via node" else "${currentMsg.relayHops} -> Relay"
                database.messageDao().updateMessageRelay(messageId, DeliveryStatus.RELAYED, updatedHops)
            }
            AckType.DELIVERED -> {
                database.messageDao().updateMessageState(messageId, DeliveryStatus.DELIVERED)
            }
            AckType.READ -> {
                database.messageDao().updateMessageState(messageId, DeliveryStatus.READ)
            }
        }
    }

    suspend fun sendAck(
        targetDeviceId: String,
        originalMessageId: String,
        originalPacketId: String,
        ackType: AckType
    ) {
        val ackPacket = MeshPacket(
            packetType = PacketType.ACK,
            senderDeviceId = myDeviceId,
            recipientDeviceId = targetDeviceId,
            messageId = originalMessageId,
            ackType = ackType,
            ttl = 10
        )
        // Mark ACK packet seen locally so it doesn't loop
        markPacketSeen(ackPacket.packetId)

        val route = database.routingDao().getRoute(targetDeviceId)
        if (route != null) {
            transportManager.sendPacket(ackPacket, route.nextHopDeviceId, route.transport)
        } else {
            transportManager.sendPacket(ackPacket, targetDeviceId)
        }
    }

    suspend fun routeOutboxMessage(message: MessageEntity, encryptedBytes: ByteArray, nonce: ByteArray, authTag: ByteArray) {
        val packet = MeshPacket(
            packetType = PacketType.MESSAGE,
            messageId = message.messageId,
            conversationId = message.conversationId,
            senderDeviceId = myDeviceId,
            recipientDeviceId = message.recipientDeviceId,
            encryptedPayload = encryptedBytes,
            nonce = nonce,
            authTag = authTag,
            ttl = message.ttl
        )
        markPacketSeen(packet.packetId)
        database.messageDao().updateMessageState(message.messageId, DeliveryStatus.SENDING)

        val route = database.routingDao().getRoute(message.recipientDeviceId)
        val success = if (route != null) {
            transportManager.sendPacket(packet, route.nextHopDeviceId, route.transport)
        } else {
            transportManager.sendPacket(packet, message.recipientDeviceId)
        }

        if (!success) {
            // Keep in queue or routing status
            database.messageDao().updateMessageState(message.messageId, DeliveryStatus.ROUTING)
        }
    }
}
