# Offline Mesh Chat

**Offline Mesh Chat** is a production-grade, local-first Android messaging application designed to operate with zero Internet, cellular data, or cloud servers. It forms decentralized peer-to-peer mesh networks over Bluetooth Low Energy (BLE), Classic Bluetooth RFCOMM, and Wi-Fi Direct (P2P), using opportunistic store-and-forward routing with end-to-end authenticated cryptography.

---

## Key Capabilities

- **Zero Cloud & Zero Internet:** Functions fully in airplane mode with local radios enabled. No centralized servers, accounts, or telemetry.
- **Unified Dual-Transport Engine:** Bluetooth and Wi-Fi Direct transports plug interchangeably into a decoupled messaging engine.
- **Opportunistic Store-and-Forward Routing:** Enables multi-hop communication ($A \rightarrow B \rightarrow C \rightarrow D$) where intermediate nodes store, relay, and forward messages without being able to read plaintext contents.
- **Cryptographic Device Identities:** Locally generated public/private key pairs (Ed25519/ECDH and AES-256-GCM) with tamper-resistant identities and verification fingerprints.
- **Multi-Level Deduplication & TTL:** Loop prevention with seen-message bloom/hash sets, message sequence tracking, and hop-count decrements.
- **Full Offline Persistence:** Powered by Android Room Database with reactive Kotlin Flows and transactional integrity.
- **Four-Tier Delivery Lifecycle:** Explicit state machine: `QUEUED` $\rightarrow$ `DISCOVERING` $\rightarrow$ `ROUTING` $\rightarrow$ `SENDING` $\rightarrow$ `RELAYED` $\rightarrow$ `DELIVERED` $\rightarrow$ `READ`.
- **Modern Jetpack Compose UI:** Material 3 design, real-time nearby peer radar, mesh topology status, transport selector, and security verification.

---

## Transports Supported

1. **Bluetooth / BLE Transport:**
   - BLE GATT advertising and discovery for low-power peer presence.
   - RFCOMM / L2CAP socket data channels for high-throughput framing and packet transfer.
2. **Wi-Fi Direct Transport:**
   - `WifiP2pManager` peer discovery and autonomous group negotiation.
   - Link-local TCP socket framing over peer connections.
3. **Transport Selection Modes:**
   - `AUTO`: Intelligent capability-based transport selection with fallback.
   - `BLUETOOTH`: Enforce Bluetooth-only transmissions.
   - `WI_FI_DIRECT`: Enforce Wi-Fi Direct high-speed transmissions.

---

## Getting Started

1. Clone or open the repository in Android Studio.
2. Build via Gradle: `./gradlew assembleDebug` or run through AI Studio preview.
3. Grant nearby device / Bluetooth and Wi-Fi permissions.
4. Set your local display handle (no phone numbers or email needed).
5. Open on two or more devices to automatically discover and form local mesh links.

---

## Multi-Hop Acceptance Testing ($A \rightarrow B \rightarrow C \rightarrow D$)

The application includes an in-app **Mesh Multi-Hop Studio** (accessible via the flask icon in the top app bar) allowing instant interactive execution and audit of the critical acceptance scenario:

- **Scenario 1: Bluetooth Multi-Hop** ($A \rightarrow B \rightarrow C \rightarrow D$)
- **Scenario 2: Wi-Fi Direct Multi-Hop** ($A \rightarrow B \rightarrow C \rightarrow D$)
- **Scenario 3: Mixed-Transport Multi-Hop** ($A \xrightarrow{\text{BT}} B \xrightarrow{\text{Wi-Fi}} C \xrightarrow{\text{BT}} D$)

### Verification Assurances:
1. **Plaintext Isolation:** Nodes $B$ and $C$ inspect the forwarded payload and display `ENCRYPTED_BLOB [Ciphertext: ...] (Access Denied)`.
2. **Delivery to Destination:** Node $D$ receives the packet and successfully deciphers `"Hello from A"` using its authenticated ECDH secret.
3. **End-to-End Delivery Receipt:** Node $D$ issues a signed `DELIVERED` ACK traversing back to Node $A$, updating the local message status to `DELIVERED`.

---

## Project Structure & Architecture

```
com.example/
├── core/
│   ├── common/        # Shared enums (DeliveryStatus, TransportType, PacketType, AckType)
│   ├── crypto/        # CryptoManager (secp256r1 ECDH, AES-256-GCM authenticated cipher)
│   └── model/         # MeshPacket wire binary format with TTL and hop limits
├── database/          # Room DB (Entities, Converters, DAOs for messages, peers, identities)
├── messaging/         # MessageEngine (local-first queue, outbox processing, seal/unseal)
├── routing/           # MeshRouter (neighbor table, store-and-forward, deduplication, ACK engine)
├── simulation/        # MeshSimulationEngine (acceptance tests & 20/50/100 node stress test)
├── transport/
│   ├── api/           # ITransport abstraction interface & TransportStatus
│   ├── bluetooth/     # BLE discovery, advertising, and RFCOMM framing
│   ├── wifidirect/    # WifiP2pManager discovery, group negotiation, socket framing
│   └── TransportManager.kt # Unified dual-link coordinator (Auto / BT / Wi-Fi Direct)
└── ui/
    ├── screens/       # HomeScreen, ChatScreen, NearbyPeersScreen, TransportStatusScreen, etc.
    ├── theme/         # Material 3 color palette & typography
    ├── MeshChatApp.kt # Permission coordinator & navigation
    └── MeshChatViewModel.kt # Unified MVVM state coordinator
```
