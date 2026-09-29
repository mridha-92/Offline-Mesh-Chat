# Security Architecture: Offline Mesh Chat

## 1. Cryptographic Primitives

- **Asymmetric Key Establishment:** ECDH (Elliptic Curve Diffie-Hellman) using NIST P-256 (`secp256r1`) or X25519 for ephemeral/static key agreements.
- **Symmetric Authenticated Encryption:** AES-GCM (Galois/Counter Mode) with 256-bit keys and 128-bit authentication tags.
- **Integrity & Fingerprints:** SHA-256 digests used for public key verification strings (human-readable safety numbers).
- **Nonce Strategy:** Cryptographically secure 96-bit (12-byte) random initialization vectors (IVs) generated via `SecureRandom` per message, preventing keystream reuse.

---

## 2. Relay Privacy & Zero-Knowledge Hops

In an $A \rightarrow B \rightarrow C \rightarrow D$ relay path:
- $A$ encrypts the plaintext payload using an authenticated secret shared exclusively between $A$ and recipient $D$.
- Relay nodes $B$ and $C$ inspect only the routing envelope (`recipientDeviceId`, `ttl`, `hopCount`, `packetId`).
- Relay nodes have no mathematical access to decipher the encrypted payload.
- Message payloads cannot be tampered with in transit without failing AES-GCM authentication tag verification at destination node $D$.

---

## 3. Replay Protection & Loop Prevention

- **Unique Packet IDs:** UUIDv4 generation for every envelope.
- **Deduplication Bloom/Hash Cache:** Maintained in memory and persisted in Room (`MessageSeen` entity) to drop already-processed or replayed packets.
- **Strict TTL:** Enforces maximum hop distance (default: 10 hops) and maximum message age (expiration timestamp).
- **No Plaintext Logging:** Strict sanitization policies prevent sensitive payloads or private keys from appearing in Logcat.
