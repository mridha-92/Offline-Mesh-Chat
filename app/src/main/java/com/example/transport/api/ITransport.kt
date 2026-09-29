package com.example.transport.api

import com.example.core.model.MeshPacket
import com.example.core.model.TransportType
import com.example.database.PeerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class TransportStatus(
    val transportType: TransportType,
    val isAvailable: Boolean = false,
    val isEnabled: Boolean = false,
    val isDiscovering: Boolean = false,
    val isAdvertising: Boolean = false,
    val connectedPeerCount: Int = 0,
    val statusMessage: String = "Initialized"
)

interface ITransport {
    val transportType: TransportType
    val status: StateFlow<TransportStatus>
    val incomingPackets: Flow<MeshPacket>
    val discoveredPeers: Flow<List<PeerEntity>>

    suspend fun initialize(): Boolean
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun startAdvertising(displayName: String, deviceId: String)
    suspend fun stopAdvertising()
    suspend fun connect(peerId: String): Boolean
    suspend fun disconnect(peerId: String)
    suspend fun sendPacket(packet: MeshPacket, targetPeerId: String): Boolean
    suspend fun release()
}
