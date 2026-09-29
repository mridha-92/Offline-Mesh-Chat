# Test Strategy & Multi-Hop Validation

## Test Hierarchy

1. **Unit Tests:**
   - Cryptographic primitives: Key-pair generation, ECDH secret agreement, AES-256-GCM authenticated encryption/decryption, tamper detection.
   - Wire framing & serialization: Packet encoding, decoding, corrupted buffer recovery.
   - Deduplication & TTL: Loop avoidance, hop decrement, expiration drops.
   - Room Database: Entities, DAOs, reactive flow emissions.

2. **Integration & Multi-Hop Simulation Tests:**
   - Direct Delivery ($A \rightarrow B$).
   - 2-Hop Relay ($A \rightarrow B \rightarrow C$).
   - 3-Hop Multi-Hop Relay ($A \rightarrow B \rightarrow C \rightarrow D$):
     - Validates store-and-forward relay when $D$ is out of radio range of $A$.
     - Asserts intermediate nodes ($B, C$) store and forward the encrypted envelope without plaintext access.
     - Asserts $D$ receives and decrypts "Hello from A".
     - Validates end-to-end delivery receipt ACK returned back to $A$.
   - Transport resilience: Automatic fallback between Bluetooth and Wi-Fi Direct.
