# System Architecture: Offline Mesh Chat

## 1. High-Level Architecture

The system decouples the presentation and message orchestration from the underlying peer-to-peer radio transports:

```
┌────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                   │
│   (Home, Chat, Nearby Peers, Network Status, Settings) │
└───────────────────────────┬────────────────────────────┘
                            │ ViewModel / StateFlow
┌───────────────────────────▼────────────────────────────┐
│                    Messaging Engine                    │
│    - Outbox/Inbox Processing                           │
│    - Deduplication Index                               │
│    - Message State Machine (QUEUED -> READ)            │
│    - End-to-End Cryptographic Seal/Open                │
└─────────────┬────────────────────────────┬─────────────┘
              │                            │
┌─────────────▼────────────┐  ┌────────────▼─────────────┐
│  Store & Forward Router  │  │   Room Local Database    │
│  - Neighbor Table        │  │  - Messages & Delivery   │
│  - TTL & Hop Decrement   │  │  - Peers & Devices       │
│  - Routing Cache         │  │  - Conversations        │
│  - Ack & Receipt Engine  │  │  - Crypto Identity       │
└─────────────┬────────────┘  └──────────────────────────┘
              │
┌─────────────▼────────────┐
│    Transport Manager     │
│  - Auto / Manual Select  │
│  - Multi-Transport Fallback
└─────────────┬────────────┘
              │
       ┌──────┴──────────────────────────┐
       │                                 │
┌──────▼─────────────────┐     ┌─────────▼─────────────────┐
│   BluetoothTransport   │     │    WifiDirectTransport    │
│  - BLE Discovery       │     │  - Wi-Fi P2P Group / Peer │
│  - RFCOMM Framing      │     │  - TCP Streaming Channel  │
└────────────────────────┘     └───────────────────────────┘
```

---

## 2. Core Modules & Packages

- `com.example.core.common`: Result types, utilities, constants, base dispatchers.
- `com.example.core.crypto`: ECDH key-pair generation, AES-GCM-256 authenticated encryption, SHA-256 fingerprinting, nonce & replay guard.
- `com.example.core.model`: Pure domain entities (`Device`, `Peer`, `Message`, `Packet`, `DeliveryStatus`, `PayloadType`).
- `com.example.database`: Room Database, DAOs (`MessageDao`, `PeerDao`, `ConversationDao`, `IdentityDao`), TypeConverters.
- `com.example.transport.api`: `ITransport`, `TransportType`, `TransportStatus`, `TransportManager`.
- `com.example.transport.bluetooth`: Bluetooth BLE advertising/scanning + RFCOMM framing.
- `com.example.transport.wifidirect`: Wi-Fi P2P manager + TCP framing.
- `com.example.routing`: Store-and-forward mesh router, neighbor table, TTL decrement, relay forwarder, ACK pipeline.
- `com.example.messaging`: MessageEngine coordinating queue dispatch, local persistence, encryption seal, reception unwrapping.
- `com.example.ui`: Jetpack Compose UI with Navigation (Home, Chat, Peers Radar, Mesh Status, Identity & Settings).

---

## 3. Communication Protocol & Framing

Network packets are framed with a structured binary/header wire format:
- `protocolVersion`: Byte (currently 1)
- `packetType`: HELLO (1), PEER_ADV (2), MESSAGE (3), ACK (4), ROUTE_DISCOVERY (5)
- `packetId`: UUID string (36 bytes)
- `senderDeviceId`: Hex/UUID string
- `recipientDeviceId`: Target device ID (or `*` for broadcast)
- `hopCount`: Int (increments per hop, max 10)
- `ttl`: Int (decrements, dropped when <= 0)
- `payloadLength`: Int
- `payload`: Encrypted byte payload
- `authTag`: Cryptographic authentication tag / HMAC
