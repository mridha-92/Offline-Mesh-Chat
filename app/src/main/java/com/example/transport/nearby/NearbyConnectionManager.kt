package com.example.transport.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Connection states for individual Nearby endpoints.
 */
enum class NearbyConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    REJECTED,
    FAILED
}

/**
 * Underlying radio mediums supported and coordinated by Nearby Connections.
 */
enum class NearbyMedium(val displayName: String) {
    BLUETOOTH("Bluetooth (BLE / Classic)"),
    WIFI_DIRECT("Wi-Fi Direct / Local Hotspot"),
    HYBRID_AUTO("Bluetooth & Wi-Fi Direct (Auto)")
}

/**
 * Event emitted when an endpoint's connection state transitions.
 */
data class ConnectionStateChangeEvent(
    val endpointId: String,
    val endpointName: String,
    val state: NearbyConnectionState,
    val medium: NearbyMedium = NearbyMedium.HYBRID_AUTO,
    val statusMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Metadata for an endpoint discovered in the local radio perimeter.
 */
data class DiscoveredEndpoint(
    val endpointId: String,
    val endpointName: String,
    val serviceId: String,
    val medium: NearbyMedium = NearbyMedium.HYBRID_AUTO,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * State information for an active or pending endpoint connection.
 */
data class ConnectionStateInfo(
    val endpointId: String,
    val endpointName: String,
    val isConnected: Boolean,
    val state: NearbyConnectionState = if (isConnected) NearbyConnectionState.CONNECTED else NearbyConnectionState.DISCONNECTED,
    val statusMessage: String = "",
    val medium: NearbyMedium = NearbyMedium.HYBRID_AUTO,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Encapsulates initialization, advertising, device discovery, connection states,
 * and payload transfers for the Google Nearby Connections API (`play-services-nearby`).
 *
 * Utilizes [Strategy.P2P_CLUSTER] to form an ad-hoc, decentralized mesh network
 * bridging both Bluetooth (for zero-setup beaconing and discovery) and Wi-Fi Direct
 * (for high-throughput local data transfers).
 */
class NearbyConnectionManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val TAG = "NearbyConnMgr"
        const val DEFAULT_SERVICE_ID = "com.aistudio.meshchat.SERVICE_ID"

        /**
         * Strategy.P2P_CLUSTER: Decentralized M-to-N mesh topology supporting simultaneous
         * advertising, discovery, and multi-endpoint data exchange over Bluetooth & Wi-Fi Direct.
         */
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }

    private var connectionsClient: ConnectionsClient? = null

    // Reactive StateFlows
    private val _isGmsAvailable = MutableStateFlow(false)
    val isGmsAvailable: StateFlow<Boolean> = _isGmsAvailable.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<Map<String, DiscoveredEndpoint>>(emptyMap())
    val discoveredEndpoints: StateFlow<Map<String, DiscoveredEndpoint>> = _discoveredEndpoints.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Map<String, ConnectionStateInfo>>(emptyMap())
    val connectedEndpoints: StateFlow<Map<String, ConnectionStateInfo>> = _connectedEndpoints.asStateFlow()

    private val _connectionEvents = MutableSharedFlow<ConnectionStateChangeEvent>(extraBufferCapacity = 64)
    val connectionEvents: SharedFlow<ConnectionStateChangeEvent> = _connectionEvents.asSharedFlow()

    private val _receivedBytePayloads = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 128)
    val receivedBytePayloads: SharedFlow<Pair<String, ByteArray>> = _receivedBytePayloads.asSharedFlow()

    private val _statusMessage = MutableStateFlow("Uninitialized")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var currentLocalEndpointName: String = "MeshNode"
    private val pendingConnections = ConcurrentHashMap<String, String>()

    private fun getClient(): ConnectionsClient? {
        if (connectionsClient == null) {
            try {
                connectionsClient = Nearby.getConnectionsClient(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Nearby.getConnectionsClient unavailable: ${t.message}")
            }
        }
        return connectionsClient
    }

    /**
     * Checks if all required Bluetooth permissions are granted based on Android OS version.
     */
    fun hasBluetoothPermissions(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if all required Wi-Fi Direct permissions are granted based on Android OS version.
     */
    fun hasWifiPermissions(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if all permissions for both Bluetooth and Wi-Fi Direct are granted.
     */
    fun hasPermissions(): Boolean {
        return hasBluetoothPermissions() && hasWifiPermissions()
    }

    /**
     * Verifies Google Play Services availability and prepares the Nearby client safely.
     */
    fun initialize(): Boolean {
        return try {
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
            val available = (resultCode == ConnectionResult.SUCCESS)
            _isGmsAvailable.value = available

            if (available) {
                getClient()
                _statusMessage.value = "Nearby Connections active (Bluetooth & Wi-Fi Direct P2P)"
            } else {
                _statusMessage.value = "Nearby unavailable on device (code $resultCode). Local mesh active."
            }
            available
        } catch (t: Throwable) {
            Log.w(TAG, "Error checking Google Play Services: ${t.message}")
            _isGmsAvailable.value = false
            _statusMessage.value = "Nearby unavailable. Local mesh active."
            false
        }
    }

    /**
     * Payload callback to receive streaming byte arrays from connected mesh peers.
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                if (bytes != null) {
                    coroutineScope.launch {
                        _receivedBytePayloads.emit(endpointId to bytes)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "Payload successfully transferred with $endpointId")
            }
        }
    }

    /**
     * Connection lifecycle callback tracking initiated connections, authentication, and results.
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            val remoteName = connectionInfo.endpointName.ifBlank { "Peer ($endpointId)" }
            Log.d(TAG, "Connection initiated: $endpointId ($remoteName)")
            pendingConnections[endpointId] = remoteName

            // Record connecting state
            _connectedEndpoints.update { current ->
                current + (endpointId to ConnectionStateInfo(
                    endpointId = endpointId,
                    endpointName = remoteName,
                    isConnected = false,
                    state = NearbyConnectionState.CONNECTING,
                    statusMessage = "Initiating connection over Bluetooth/Wi-Fi...",
                    medium = NearbyMedium.HYBRID_AUTO
                ))
            }

            coroutineScope.launch {
                _connectionEvents.emit(
                    ConnectionStateChangeEvent(
                        endpointId = endpointId,
                        endpointName = remoteName,
                        state = NearbyConnectionState.CONNECTING,
                        medium = NearbyMedium.HYBRID_AUTO,
                        statusMessage = "Connection initiated"
                    )
                )
            }

            // Automatically accept in P2P Cluster mode to sustain open peer mesh
            acceptConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val name = pendingConnections.remove(endpointId) ?: "Peer ($endpointId)"
            if (resolution.status.isSuccess) {
                Log.d(TAG, "Connected successfully to $endpointId")
                val stateInfo = ConnectionStateInfo(
                    endpointId = endpointId,
                    endpointName = name,
                    isConnected = true,
                    state = NearbyConnectionState.CONNECTED,
                    statusMessage = "Connected via Bluetooth / Wi-Fi Direct",
                    medium = NearbyMedium.HYBRID_AUTO
                )
                _connectedEndpoints.update { current -> current + (endpointId to stateInfo) }

                coroutineScope.launch {
                    _connectionEvents.emit(
                        ConnectionStateChangeEvent(
                            endpointId = endpointId,
                            endpointName = name,
                            state = NearbyConnectionState.CONNECTED,
                            medium = NearbyMedium.HYBRID_AUTO,
                            statusMessage = "Connected successfully"
                        )
                    )
                }
            } else {
                Log.w(TAG, "Connection to $endpointId failed: ${resolution.status.statusMessage}")
                val isRejected = resolution.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED
                val finalState = if (isRejected) NearbyConnectionState.REJECTED else NearbyConnectionState.FAILED

                _connectedEndpoints.update { current -> current - endpointId }

                coroutineScope.launch {
                    _connectionEvents.emit(
                        ConnectionStateChangeEvent(
                            endpointId = endpointId,
                            endpointName = name,
                            state = finalState,
                            medium = NearbyMedium.HYBRID_AUTO,
                            statusMessage = resolution.status.statusMessage ?: "Connection failed"
                        )
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            val priorInfo = _connectedEndpoints.value[endpointId]
            val name = priorInfo?.endpointName ?: pendingConnections.remove(endpointId) ?: "Peer ($endpointId)"

            _connectedEndpoints.update { current -> current - endpointId }

            coroutineScope.launch {
                _connectionEvents.emit(
                    ConnectionStateChangeEvent(
                        endpointId = endpointId,
                        endpointName = name,
                        state = NearbyConnectionState.DISCONNECTED,
                        medium = NearbyMedium.HYBRID_AUTO,
                        statusMessage = "Disconnected from mesh peer"
                    )
                )
            }
        }
    }

    /**
     * Endpoint discovery callback triggered when nearby devices are discovered or lost.
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName.ifBlank { "Nearby Peer" }
            Log.d(TAG, "Discovered endpoint: $endpointId ($peerName)")

            val endpoint = DiscoveredEndpoint(
                endpointId = endpointId,
                endpointName = peerName,
                serviceId = info.serviceId,
                medium = NearbyMedium.HYBRID_AUTO
            )

            _discoveredEndpoints.update { current -> current + (endpointId to endpoint) }

            // Automatically request connection in P2P Cluster to establish mesh links
            requestConnection(endpointId, currentLocalEndpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost discovered endpoint: $endpointId")
            _discoveredEndpoints.update { current -> current - endpointId }
        }
    }

    /**
     * Starts advertising presence on Nearby Connections.
     * Nearby Connections automatically engages Bluetooth LE advertising and, where supported,
     * Wi-Fi Direct service registration.
     */
    fun startAdvertising(
        localDisplayName: String,
        serviceId: String = DEFAULT_SERVICE_ID,
        strategy: Strategy = STRATEGY
    ) {
        if (!_isGmsAvailable.value) {
            _statusMessage.value = "Google Play Services not available for Nearby advertising"
            return
        }
        if (!hasPermissions()) {
            _statusMessage.value = "Nearby waiting for Bluetooth/Wi-Fi permissions"
            return
        }
        val client = getClient() ?: return
        currentLocalEndpointName = localDisplayName

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        try {
            client.startAdvertising(
                localDisplayName,
                serviceId,
                connectionLifecycleCallback,
                advertisingOptions
            ).addOnSuccessListener {
                _isAdvertising.value = true
                _statusMessage.value = "Advertising presence as '$localDisplayName' (BT & Wi-Fi Direct)"
            }.addOnFailureListener { e ->
                _isAdvertising.value = false
                _statusMessage.value = "Advertising inactive: ${e.message}"
                Log.w(TAG, "startAdvertising failed: ${e.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startAdvertising caught error: ${t.message}")
            _isAdvertising.value = false
        }
    }

    /**
     * Stops advertising presence safely.
     */
    fun stopAdvertising() {
        try {
            getClient()?.stopAdvertising()
        } catch (_: Throwable) {}
        _isAdvertising.value = false
    }

    /**
     * Starts discovering nearby mesh endpoints across Bluetooth and Wi-Fi Direct.
     */
    fun startDiscovery(
        serviceId: String = DEFAULT_SERVICE_ID,
        strategy: Strategy = STRATEGY
    ) {
        if (!_isGmsAvailable.value) {
            _statusMessage.value = "Google Play Services not available for Nearby discovery"
            return
        }
        if (!hasPermissions()) {
            _statusMessage.value = "Nearby waiting for Bluetooth/Wi-Fi permissions"
            return
        }
        val client = getClient() ?: return

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        try {
            client.startDiscovery(
                serviceId,
                endpointDiscoveryCallback,
                discoveryOptions
            ).addOnSuccessListener {
                _isDiscovering.value = true
                _statusMessage.value = "Discovering peers over Bluetooth & Wi-Fi Direct..."
            }.addOnFailureListener { e ->
                _isDiscovering.value = false
                _statusMessage.value = "Discovery inactive: ${e.message}"
                Log.w(TAG, "startDiscovery failed: ${e.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startDiscovery caught error: ${t.message}")
            _isDiscovering.value = false
        }
    }

    /**
     * Stops discovering nearby mesh endpoints.
     */
    fun stopDiscovery() {
        try {
            getClient()?.stopDiscovery()
        } catch (_: Throwable) {}
        _isDiscovering.value = false
    }

    /**
     * Requests a connection to a discovered endpoint.
     */
    fun requestConnection(endpointId: String, localDisplayName: String = currentLocalEndpointName) {
        if (!_isGmsAvailable.value) return
        val client = getClient() ?: return

        try {
            client.requestConnection(
                localDisplayName,
                endpointId,
                connectionLifecycleCallback
            ).addOnSuccessListener {
                Log.d(TAG, "Connection requested to $endpointId")
            }.addOnFailureListener { e ->
                Log.w(TAG, "Connection request to $endpointId failed: ${e.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error in requestConnection: ${t.message}")
        }
    }

    /**
     * Explicitly accepts an incoming connection from an endpoint.
     */
    fun acceptConnection(endpointId: String) {
        val client = getClient() ?: return
        try {
            client.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "Accepted connection from $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed accepting connection from $endpointId: ${e.message}")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Exception accepting connection: ${t.message}")
        }
    }

    /**
     * Rejects an incoming connection request.
     */
    fun rejectConnection(endpointId: String) {
        val client = getClient() ?: return
        try {
            client.rejectConnection(endpointId)
                .addOnSuccessListener {
                    Log.d(TAG, "Rejected connection from $endpointId")
                    pendingConnections.remove(endpointId)
                    _connectedEndpoints.update { it - endpointId }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed rejecting connection from $endpointId: ${e.message}")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Exception rejecting connection: ${t.message}")
        }
    }

    /**
     * Disconnects from a specific endpoint.
     */
    fun disconnect(endpointId: String) {
        try {
            getClient()?.disconnectFromEndpoint(endpointId)
        } catch (_: Throwable) {}
        _connectedEndpoints.update { it - endpointId }
    }

    /**
     * Sends bytes to a single connected endpoint via Bluetooth / Wi-Fi Direct.
     */
    fun sendBytes(endpointId: String, bytes: ByteArray): Boolean {
        if (!_connectedEndpoints.value.containsKey(endpointId)) return false
        val client = getClient() ?: return false
        return try {
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Error sending payload to $endpointId: ${t.message}")
            false
        }
    }

    /**
     * Broadcasts bytes to all currently connected endpoints.
     */
    fun broadcastBytes(bytes: ByteArray): Boolean {
        val targets = _connectedEndpoints.value.keys.toList()
        if (targets.isEmpty()) return false
        val client = getClient() ?: return false
        return try {
            client.sendPayload(targets, Payload.fromBytes(bytes))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Error broadcasting payload: ${t.message}")
            false
        }
    }

    /**
     * Releases all endpoints, stops discovery and advertising, and resets state.
     */
    fun release() {
        stopDiscovery()
        stopAdvertising()
        try {
            getClient()?.stopAllEndpoints()
        } catch (_: Throwable) {}
        _connectedEndpoints.value = emptyMap()
        _discoveredEndpoints.value = emptyMap()
        pendingConnections.clear()
        _statusMessage.value = "Released"
    }
}
