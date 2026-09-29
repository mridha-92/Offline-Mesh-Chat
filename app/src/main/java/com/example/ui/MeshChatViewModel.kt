package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.DeliveryStatus
import com.example.core.model.TransportType
import com.example.database.ConversationEntity
import com.example.database.CryptoIdentityEntity
import com.example.database.MessageEntity
import com.example.database.MeshDatabase
import com.example.database.PeerEntity
import com.example.messaging.MessageEngine
import com.example.simulation.MeshSimulationEngine
import com.example.simulation.SimulationResult
import com.example.transport.TransportManager
import com.example.transport.api.TransportStatus
import com.example.transport.bluetooth.BluetoothTransport
import com.example.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiNotification(
    val id: String = java.util.UUID.randomUUID().toString(),
    val message: String
)

class MeshChatViewModel(application: Application) : AndroidViewModel(application) {

    val database = MeshDatabase.getInstance(application)
    val nearbyTransport = com.example.transport.nearby.NearbyConnectionsTransport(application, viewModelScope)
    val bluetoothTransport = BluetoothTransport(application, viewModelScope)
    val wifiDirectTransport = WifiDirectTransport(application, viewModelScope)
    val transportManager = TransportManager(bluetoothTransport, wifiDirectTransport, nearbyTransport, viewModelScope)
    val messageEngine = MessageEngine(application, database, transportManager, viewModelScope)

    val currentIdentity: StateFlow<CryptoIdentityEntity?> = messageEngine.currentIdentity
    val activeTransportMode: StateFlow<TransportType> = transportManager.transportMode
    val nearbyStatus: StateFlow<TransportStatus>? = transportManager.nearbyStatus
    val bluetoothStatus: StateFlow<TransportStatus> = transportManager.bluetoothStatus
    val wifiDirectStatus: StateFlow<TransportStatus> = transportManager.wifiDirectStatus

    val conversations: StateFlow<List<ConversationEntity>> = messageEngine.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discoveredPeers: StateFlow<List<PeerEntity>> = transportManager.allDiscoveredPeers

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeConversationMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val activeConversationMessages: StateFlow<List<MessageEntity>> = _activeConversationMessages.asStateFlow()

    private val _isRelayEnabled = MutableStateFlow(true)
    val isRelayEnabled: StateFlow<Boolean> = _isRelayEnabled.asStateFlow()

    private val _latestSimulationResult = MutableStateFlow<SimulationResult?>(null)
    val latestSimulationResult: StateFlow<SimulationResult?> = _latestSimulationResult.asStateFlow()

    private val _stressTestMetrics = MutableStateFlow<Map<String, Any>?>(null)
    val stressTestMetrics: StateFlow<Map<String, Any>?> = _stressTestMetrics.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                transportManager.initialize()
            } catch (e: Exception) {
                android.util.Log.w("MeshChatVM", "Transport initialization non-fatal: ${e.message}")
            }
        }
    }

    fun startMeshDiscovery() {
        viewModelScope.launch {
            try {
                transportManager.startDiscovery()
            } catch (e: Exception) {
                android.util.Log.w("MeshChatVM", "startDiscovery non-fatal: ${e.message}")
            }
        }
    }

    fun openConversation(conversationId: String) {
        _activeConversationId.value = conversationId
        viewModelScope.launch {
            messageEngine.getConversationMessages(conversationId).collect { msgs ->
                _activeConversationMessages.value = msgs
            }
        }
    }

    fun startConversationWithPeer(peer: PeerEntity) {
        val convId = "conv_${peer.deviceId}"
        viewModelScope.launch {
            database.conversationDao().upsertConversation(
                ConversationEntity(
                    conversationId = convId,
                    peerDeviceId = peer.deviceId,
                    peerDisplayName = peer.displayName,
                    lastMessage = "Started conversation",
                    updatedAt = System.currentTimeMillis()
                )
            )
            openConversation(convId)
        }
    }

    fun sendMessage(text: String, peerDeviceId: String) {
        val convId = _activeConversationId.value ?: return
        if (text.isBlank()) return

        viewModelScope.launch {
            messageEngine.sendMessage(
                conversationId = convId,
                peerDeviceId = peerDeviceId,
                text = text.trim()
            )
        }
    }

    fun setTransportMode(mode: TransportType) {
        transportManager.setTransportMode(mode)
        viewModelScope.launch {
            transportManager.startDiscovery()
        }
    }

    fun toggleRelayParticipation(enabled: Boolean) {
        _isRelayEnabled.value = enabled
        messageEngine.router?.isRelayEnabled = enabled
    }

    fun verifyPeer(deviceId: String, isVerified: Boolean) {
        viewModelScope.launch {
            database.peerDao().setPeerVerified(deviceId, isVerified)
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            messageEngine.updateDisplayName(name)
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            messageEngine.clearAllData()
            _activeConversationId.value = null
            _activeConversationMessages.value = emptyList()
        }
    }

    fun runAcceptanceTest(mode: String) {
        _isSimulating.value = true
        viewModelScope.launch {
            val result = MeshSimulationEngine.runMultiHopAcceptanceTest(mode, "Hello from A")
            _latestSimulationResult.value = result
            _isSimulating.value = false
        }
    }

    fun runStressTest(nodes: Int) {
        _isSimulating.value = true
        viewModelScope.launch {
            val metrics = MeshSimulationEngine.runStressTest(nodes)
            _stressTestMetrics.value = metrics
            _isSimulating.value = false
        }
    }
}
