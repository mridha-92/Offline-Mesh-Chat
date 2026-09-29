package com.example.transport.nearby

import android.content.Context
import android.util.Log
import com.example.core.model.MeshPacket
import com.example.core.model.TransportType
import com.example.database.PeerEntity
import com.example.transport.api.ITransport
import com.example.transport.api.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of ITransport backed by NearbyConnectionManager.
 * Bridges the encapsulated Google Nearby Connections API into the
 * local-first Mesh Chat architecture.
 */
class NearbyConnectionsTransport(
    context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    val manager: NearbyConnectionManager = NearbyConnectionManager(context, coroutineScope)
) : ITransport {

    override val transportType: TransportType = TransportType.AUTO

    private val _status = MutableStateFlow(
        TransportStatus(
            transportType = TransportType.AUTO,
            statusMessage = "Nearby Connections initializing..."
        )
    )
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 128)
    override val incomingPackets: Flow<MeshPacket> = _incomingPackets.asSharedFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerEntity>>(emptyList())
    override val discoveredPeers: Flow<List<PeerEntity>> = _discoveredPeers.asStateFlow()

    private val endpointToPeerMap = ConcurrentHashMap<String, PeerEntity>()
    private val peerIdToEndpointMap = ConcurrentHashMap<String, String>()

    companion object {
        const val TAG = "NearbyTransport"
    }

    init {
        // Collect incoming byte payloads from manager and decode into MeshPackets
        coroutineScope.launch {
            manager.receivedBytePayloads.collect { (endpointId, bytes) ->
                try {
                    val packet = MeshPacket.fromByteArray(bytes)
                    _incomingPackets.emit(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing MeshPacket from $endpointId: ${e.message}")
                }
            }
        }

        // Synchronize discovered endpoints
        coroutineScope.launch {
            manager.discoveredEndpoints.collect { endpointsMap ->
                endpointsMap.forEach { (endpointId, info) ->
                    val peerId = "nearby_${endpointId.lowercase()}"
                    val peer = PeerEntity(
                        deviceId = peerId,
                        displayName = info.endpointName.ifBlank { "Nearby Peer" },
                        lastSeen = info.timestamp,
                        transport = TransportType.AUTO,
                        connectionState = "DISCOVERED",
                        signalStrength = -55,
                        canRelay = true
                    )
                    endpointToPeerMap[endpointId] = peer
                    peerIdToEndpointMap[peerId] = endpointId
                }
                _discoveredPeers.value = endpointToPeerMap.values.toList()
            }
        }

        // Synchronize connected endpoints
        coroutineScope.launch {
            manager.connectedEndpoints.collect { connectedMap ->
                connectedMap.forEach { (endpointId, connInfo) ->
                    val peerId = "nearby_${endpointId.lowercase()}"
                    val peer = PeerEntity(
                        deviceId = peerId,
                        displayName = connInfo.endpointName.ifBlank { "Nearby Peer" },
                        lastSeen = System.currentTimeMillis(),
                        transport = TransportType.AUTO,
                        connectionState = if (connInfo.isConnected) "CONNECTED" else "DISCONNECTED",
                        signalStrength = -50,
                        canRelay = true
                    )
                    endpointToPeerMap[endpointId] = peer
                    peerIdToEndpointMap[peerId] = endpointId
                }
                _discoveredPeers.value = endpointToPeerMap.values.toList()
                _status.update { it.copy(connectedPeerCount = connectedMap.size) }
            }
        }

        // Synchronize status updates
        coroutineScope.launch {
            combine(
                manager.isGmsAvailable,
                manager.isAdvertising,
                manager.isDiscovering,
                manager.statusMessage
            ) { available, advertising, discovering, message ->
                TransportStatus(
                    transportType = TransportType.AUTO,
                    isAvailable = available,
                    isEnabled = available,
                    isDiscovering = discovering,
                    isAdvertising = advertising,
                    connectedPeerCount = manager.connectedEndpoints.value.size,
                    statusMessage = message
                )
            }.collect { newStatus ->
                _status.value = newStatus
            }
        }
    }

    override suspend fun initialize(): Boolean {
        return manager.initialize()
    }

    override suspend fun startDiscovery() {
        manager.startDiscovery()
    }

    override suspend fun stopDiscovery() {
        manager.stopDiscovery()
    }

    override suspend fun startAdvertising(displayName: String, deviceId: String) {
        manager.startAdvertising(displayName)
    }

    override suspend fun stopAdvertising() {
        manager.stopAdvertising()
    }

    override suspend fun connect(peerId: String): Boolean {
        val endpointId = peerIdToEndpointMap[peerId] ?: return false
        manager.requestConnection(endpointId)
        return true
    }

    override suspend fun disconnect(peerId: String) {
        val endpointId = peerIdToEndpointMap[peerId] ?: return
        manager.disconnect(endpointId)
    }

    override suspend fun sendPacket(packet: MeshPacket, targetPeerId: String): Boolean {
        val bytes = packet.toByteArray()
        val endpointId = peerIdToEndpointMap[targetPeerId]

        return if (targetPeerId == "*" || endpointId == null) {
            manager.broadcastBytes(bytes)
        } else {
            manager.sendBytes(endpointId, bytes)
        }
    }

    fun injectReceivedPacket(packet: MeshPacket) {
        coroutineScope.launch {
            _incomingPackets.emit(packet)
        }
    }

    fun injectDiscoveredPeer(peer: PeerEntity) {
        endpointToPeerMap[peer.deviceId] = peer
        _discoveredPeers.value = endpointToPeerMap.values.toList()
    }

    override suspend fun release() {
        manager.release()
        endpointToPeerMap.clear()
        peerIdToEndpointMap.clear()
    }
}
