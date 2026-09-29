package com.example.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothTransport(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ITransport {

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val _status = MutableStateFlow(
        TransportStatus(
            transportType = TransportType.BLUETOOTH,
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

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000a157-0000-1000-8000-00805f9b34fb")
    }

    override suspend fun initialize(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val hasBt = bluetoothAdapter != null
        val isEnabled = bluetoothAdapter?.isEnabled == true

        _status.update {
            it.copy(
                isAvailable = hasBt,
                isEnabled = isEnabled,
                statusMessage = when {
                    !hasBt -> "Bluetooth hardware not detected on device"
                    !isEnabled -> "Bluetooth radio is disabled"
                    else -> "Bluetooth ready"
                }
            )
        }
        return hasBt && isEnabled
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true) {
            _status.update { it.copy(statusMessage = "Discovery failed: Missing permissions or BT off") }
            return
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            _status.update { it.copy(statusMessage = "BLE Scanner unavailable") }
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { handleScanResult(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _status.update { it.copy(isDiscovering = false, statusMessage = "BLE Scan failed: code $errorCode") }
            }
        }

        try {
            bleScanner?.startScan(scanCallback)
            _status.update { it.copy(isDiscovering = true, statusMessage = "Scanning for nearby BLE mesh nodes...") }
        } catch (e: Exception) {
            _status.update { it.copy(isDiscovering = false, statusMessage = "Scan error: ${e.message}") }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName ?: device.name ?: "Mesh Peer ${device.address.takeLast(4)}"
        val peerId = "bt_${device.address.replace(":", "").lowercase()}"
        val rssi = result.rssi

        val peer = PeerEntity(
            deviceId = peerId,
            displayName = name,
            lastSeen = System.currentTimeMillis(),
            transport = TransportType.BLUETOOTH,
            connectionState = "DISCOVERED",
            signalStrength = rssi,
            canRelay = true
        )
        peerMap[peerId] = peer
        _discoveredPeers.value = peerMap.values.toList()
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        try {
            scanCallback?.let { bleScanner?.stopScan(it) }
        } catch (_: Exception) {}
        scanCallback = null
        _status.update { it.copy(isDiscovering = false) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startAdvertising(displayName: String, deviceId: String) {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true) return

        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                _status.update { it.copy(isAdvertising = true, statusMessage = "Advertising mesh presence") }
            }

            override fun onStartFailure(errorCode: Int) {
                _status.update { it.copy(isAdvertising = false, statusMessage = "Advertising failed: code $errorCode") }
            }
        }

        try {
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            _status.update { it.copy(isAdvertising = false, statusMessage = "Advertise error: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopAdvertising() {
        try {
            advertiseCallback?.let { bleAdvertiser?.stopAdvertising(it) }
        } catch (_: Exception) {}
        advertiseCallback = null
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
        // RFCOMM / L2CAP framing transmission
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
        stopDiscovery()
        stopAdvertising()
        connectedPeers.clear()
        peerMap.clear()
    }
}
