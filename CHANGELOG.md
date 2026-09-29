# Changelog

## [1.0.0] - Phase 1 through Phase 15 Initial Implementation

### Added
- **Core Architecture & Models**:
  - `CryptoIdentity`, `Device`, `Peer`, `Conversation`, `Message`, `Packet`, `DeliveryStatus`.
  - Wire framing protocol (`MeshPacket` with HELLO, MESSAGE, ACK, ROUTE_DISCOVERY).
- **Cryptographic Security Layer**:
  - `CryptoManager` with ECDH key generation, AES-256-GCM authenticated cipher, SHA-256 identity fingerprinting, replay detection.
- **Local-First Database (Room)**:
  - DAOs: `MessageDao`, `PeerDao`, `ConversationDao`, `IdentityDao`.
  - Database: `MeshDatabase` with type converters and reactive Flows.
- **Unified Transport Layer**:
  - `ITransport` abstraction with `BluetoothTransport` (BLE/RFCOMM) and `WifiDirectTransport` (Wi-Fi P2P/Sockets).
  - `TransportManager` supporting `AUTO`, `BLUETOOTH`, and `WI_FI_DIRECT` modes with seamless fallbacks.
- **Store-and-Forward Mesh Routing Engine**:
  - Multi-hop opportunistic routing with TTL decrements, neighbor table caching, loop-prevention deduplication, and ACK propagation.
  - Multi-hop simulation and critical acceptance scenario validation ($A \rightarrow B \rightarrow C \rightarrow D$).
- **Modern Jetpack Compose UI**:
  - Material 3 navigation across Home/Conversations, Chat Screen with delivery states, Nearby Radar, Network/Transport status panel, Security & Identity inspection.
  - Interactive multi-node simulation studio permitting in-app testing of multi-hop topologies ($A \rightarrow B \rightarrow C \rightarrow D$).
- **Comprehensive Test Suite**:
  - Cryptography tests, wire framing tests, multi-hop routing tests.
