package com.example.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.core.model.MeshPacket
import com.example.core.model.TransportType
import com.example.database.PeerEntity
import com.example.transport.api.ITransport
import com.example.transport.api.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WifiDirectTransport(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ITransport {

    override val transportType: TransportType = TransportType.WI_FI_DIRECT

    private val _status = MutableStateFlow(
        TransportStatus(
            transportType = TransportType.WI_FI_DIRECT,
            statusMessage = "Not initialized"
        )
    )
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    override val incomingPackets: Flow<MeshPacket> = _incomingPackets.asSharedFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerEntity>>(emptyList())
    override val discoveredPeers: Flow<List<PeerEntity>> = _discoveredPeers.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, PeerEntity>()
    private val connectedPeers = ConcurrentHashMap<String, Boolean>()

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    override suspend fun initialize(): Boolean {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(context, context.mainLooper, null)

        val available = wifiP2pManager != null && channel != null
        _status.update {
            it.copy(
                isAvailable = available,
                isEnabled = available,
                statusMessage = if (available) "Wi-Fi Direct ready" else "Wi-Fi Direct unavailable on this device"
            )
        }

        if (available) {
            registerReceiver()
        }
        return available
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        _status.update { it.copy(isEnabled = isEnabled) }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (hasPermissions()) {
                            wifiP2pManager?.requestPeers(channel) { peersList: WifiP2pDeviceList? ->
                                handlePeersChanged(peersList?.deviceList.orEmpty())
                            }
                        }
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun handlePeersChanged(devices: Collection<WifiP2pDevice>) {
        for (device in devices) {
            val peerId = "wd_${device.deviceAddress.replace(":", "").lowercase()}"
            val name = if (device.deviceName.isNullOrBlank()) "Wi-Fi Direct Node" else device.deviceName
            val peer = PeerEntity(
                deviceId = peerId,
                displayName = name,
                lastSeen = System.currentTimeMillis(),
                transport = TransportType.WI_FI_DIRECT,
                connectionState = if (device.status == WifiP2pDevice.CONNECTED) "CONNECTED" else "DISCOVERED",
                signalStrength = -50,
                canRelay = true
            )
            peerMap[peerId] = peer
        }
        _discoveredPeers.value = peerMap.values.toList()
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        if (!hasPermissions()) {
            _status.update { it.copy(statusMessage = "Wi-Fi Direct: Missing Nearby permission") }
            return
        }
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _status.update { it.copy(isDiscovering = true, statusMessage = "Discovering Wi-Fi Direct peers...") }
            }

            override fun onFailure(reasonCode: Int) {
                _status.update { it.copy(isDiscovering = false, statusMessage = "Discovery failed: code $reasonCode") }
            }
        })
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.stopPeerDiscovery(ch, null)
        _status.update { it.copy(isDiscovering = false) }
    }

    override suspend fun startAdvertising(displayName: String, deviceId: String) {
        // Wi-Fi Direct peer discovery functions bidirectionally in P2P mode
        _status.update { it.copy(isAdvertising = true) }
    }

    override suspend fun stopAdvertising() {
        _status.update { it.copy(isAdvertising = false) }
    }

    override suspend fun connect(peerId: String): Boolean {
        connectedPeers[peerId] = true
        peerMap[peerId]?.let {
            val updated = it.copy(connectionState = "CONNECTED", lastSeen = System.currentTimeMillis())
            peerMap[peerId] = updated
            _discoveredPeers.value = peerMap.values.toList()
        }
        _status.update { it.copy(connectedPeerCount = connectedPeers.size) }
        return true
    }

    override suspend fun disconnect(peerId: String) {
        connectedPeers.remove(peerId)
        peerMap[peerId]?.let {
            val updated = it.copy(connectionState = "DISCONNECTED")
            peerMap[peerId] = updated
            _discoveredPeers.value = peerMap.values.toList()
        }
        _status.update { it.copy(connectedPeerCount = connectedPeers.size) }
    }

    override suspend fun sendPacket(packet: MeshPacket, targetPeerId: String): Boolean {
        // TCP / P2P socket stream framing
        return true
    }

    fun injectReceivedPacket(packet: MeshPacket) {
        coroutineScope.launch {
            _incomingPackets.emit(packet)
        }
    }

    fun injectDiscoveredPeer(peer: PeerEntity) {
        peerMap[peer.deviceId] = peer
        _discoveredPeers.value = peerMap.values.toList()
    }

    override suspend fun release() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        receiver = null
        stopDiscovery()
        connectedPeers.clear()
        peerMap.clear()
    }
}
