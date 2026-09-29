package com.example.transport

import com.example.core.model.MeshPacket
import com.example.core.model.TransportType
import com.example.database.PeerEntity
import com.example.transport.api.ITransport
import com.example.transport.api.TransportStatus
import com.example.transport.bluetooth.BluetoothTransport
import com.example.transport.nearby.NearbyConnectionsTransport
import com.example.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransportManager(
    val bluetoothTransport: BluetoothTransport,
    val wifiDirectTransport: WifiDirectTransport,
    val nearbyTransport: NearbyConnectionsTransport? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _transportMode = MutableStateFlow(TransportType.AUTO)
    val transportMode: StateFlow<TransportType> = _transportMode.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<Pair<MeshPacket, TransportType>>(extraBufferCapacity = 128)
    val incomingPackets: Flow<Pair<MeshPacket, TransportType>> = _incomingPackets.asSharedFlow()

    val bluetoothStatus: StateFlow<TransportStatus> = bluetoothTransport.status
    val wifiDirectStatus: StateFlow<TransportStatus> = wifiDirectTransport.status
    val nearbyStatus: StateFlow<TransportStatus>? = nearbyTransport?.status

    val allDiscoveredPeers: StateFlow<List<PeerEntity>> = combine(
        bluetoothTransport.discoveredPeers,
        wifiDirectTransport.discoveredPeers,
        nearbyTransport?.discoveredPeers ?: MutableStateFlow(emptyList())
    ) { btPeers, wdPeers, nearbyPeers ->
        val merged = mutableMapOf<String, PeerEntity>()
        nearbyPeers.forEach { merged[it.deviceId] = it }
        btPeers.forEach { bt ->
            val existing = merged[bt.deviceId]
            if (existing == null || bt.lastSeen > existing.lastSeen) {
                merged[bt.deviceId] = bt
            }
        }
        wdPeers.forEach { wd ->
            val existing = merged[wd.deviceId]
            if (existing == null || wd.lastSeen > existing.lastSeen) {
                merged[wd.deviceId] = wd
            }
        }
        merged.values.sortedByDescending { it.lastSeen }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Collect from Google Nearby Connections (P2P Cluster mesh foundation)
        nearbyTransport?.let { nb ->
            scope.launch {
                nb.incomingPackets.collect { packet ->
                    if (_transportMode.value == TransportType.AUTO) {
                        _incomingPackets.emit(packet to TransportType.AUTO)
                    }
                }
            }
        }

        // Collect from Bluetooth
        scope.launch {
            bluetoothTransport.incomingPackets.collect { packet ->
                if (_transportMode.value == TransportType.AUTO || _transportMode.value == TransportType.BLUETOOTH) {
                    _incomingPackets.emit(packet to TransportType.BLUETOOTH)
                }
            }
        }

        // Collect from Wi-Fi Direct
        scope.launch {
            wifiDirectTransport.incomingPackets.collect { packet ->
                if (_transportMode.value == TransportType.AUTO || _transportMode.value == TransportType.WI_FI_DIRECT) {
                    _incomingPackets.emit(packet to TransportType.WI_FI_DIRECT)
                }
            }
        }
    }

    suspend fun initialize() {
        nearbyTransport?.initialize()
        bluetoothTransport.initialize()
        wifiDirectTransport.initialize()
    }

    fun setTransportMode(mode: TransportType) {
        _transportMode.value = mode
    }

    suspend fun startDiscovery() {
        when (_transportMode.value) {
            TransportType.AUTO -> {
                nearbyTransport?.startDiscovery()
                bluetoothTransport.startDiscovery()
                wifiDirectTransport.startDiscovery()
            }
            TransportType.BLUETOOTH -> {
                nearbyTransport?.stopDiscovery()
                bluetoothTransport.startDiscovery()
                wifiDirectTransport.stopDiscovery()
            }
            TransportType.WI_FI_DIRECT -> {
                nearbyTransport?.stopDiscovery()
                bluetoothTransport.stopDiscovery()
                wifiDirectTransport.startDiscovery()
            }
        }
    }

    suspend fun stopDiscovery() {
        nearbyTransport?.stopDiscovery()
        bluetoothTransport.stopDiscovery()
        wifiDirectTransport.stopDiscovery()
    }

    suspend fun startAdvertising(displayName: String, deviceId: String) {
        nearbyTransport?.startAdvertising(displayName, deviceId)
        bluetoothTransport.startAdvertising(displayName, deviceId)
        wifiDirectTransport.startAdvertising(displayName, deviceId)
    }

    suspend fun stopAdvertising() {
        nearbyTransport?.stopAdvertising()
        bluetoothTransport.stopAdvertising()
        wifiDirectTransport.stopAdvertising()
    }

    suspend fun sendPacket(packet: MeshPacket, targetPeerId: String, preferredTransport: TransportType = _transportMode.value): Boolean {
        return when (preferredTransport) {
            TransportType.BLUETOOTH -> {
                bluetoothTransport.sendPacket(packet, targetPeerId)
            }
            TransportType.WI_FI_DIRECT -> {
                wifiDirectTransport.sendPacket(packet, targetPeerId)
            }
            TransportType.AUTO -> {
                // Try Google Nearby Connections P2P cluster first
                val nearbySuccess = nearbyTransport?.sendPacket(packet, targetPeerId) ?: false
                if (nearbySuccess) return true

                // Fall back to Wi-Fi Direct or Bluetooth
                val wdSuccess = if (wifiDirectStatus.value.isAvailable) {
                    wifiDirectTransport.sendPacket(packet, targetPeerId)
                } else false

                if (wdSuccess) true else bluetoothTransport.sendPacket(packet, targetPeerId)
            }
        }
    }
}
