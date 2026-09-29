package com.example

import com.example.core.crypto.CryptoManager
import com.example.core.model.AckType
import com.example.core.model.MeshPacket
import com.example.core.model.PacketType
import com.example.simulation.MeshSimulationEngine
import org.junit.Assert.*
import org.junit.Test

class MeshChatArchitectureTest {

    @Test
    fun testEcKeyGenerationAndEcdhSharedSecret() {
        val alice = CryptoManager.generateEcKeyPair()
        val bob = CryptoManager.generateEcKeyPair()

        assertNotNull(alice.public)
        assertNotNull(alice.private)
        assertNotNull(bob.public)
        assertNotNull(bob.private)

        val aliceSharedSecret = CryptoManager.computeSharedSecret(alice.private, bob.public)
        val bobSharedSecret = CryptoManager.computeSharedSecret(bob.private, alice.public)

        assertArrayEquals("ECDH shared secrets must match between Alice and Bob", aliceSharedSecret, bobSharedSecret)
        assertEquals("Shared secret must be 32 bytes (256-bit AES key)", 32, aliceSharedSecret.size)
    }

    @Test
    fun testAesGcmAuthenticatedEncryptionAndDecryption() {
        val keyPairA = CryptoManager.generateEcKeyPair()
        val keyPairB = CryptoManager.generateEcKeyPair()
        val symmetricKey = CryptoManager.computeSharedSecret(keyPairA.private, keyPairB.public)

        val message = "Hello from Offline Mesh Node"
        val encResult = CryptoManager.encryptAesGcm(message.toByteArray(Charsets.UTF_8), symmetricKey)

        assertNotNull(encResult.ciphertext)
        assertEquals("GCM nonce must be 12 bytes", 12, encResult.nonce.size)
        assertEquals("GCM auth tag must be 16 bytes", 16, encResult.authTag.size)

        // Decrypt
        val decryptedBytes = CryptoManager.decryptAesGcm(
            ciphertext = encResult.ciphertext,
            nonce = encResult.nonce,
            authTag = encResult.authTag,
            symmetricKey = symmetricKey
        )
        val decryptedText = String(decryptedBytes, Charsets.UTF_8)
        assertEquals(message, decryptedText)
    }

    @Test
    fun testAesGcmTamperDetectionFails() {
        val keyPairA = CryptoManager.generateEcKeyPair()
        val keyPairB = CryptoManager.generateEcKeyPair()
        val symmetricKey = CryptoManager.computeSharedSecret(keyPairA.private, keyPairB.public)

        val message = "Top Secret Payload"
        val encResult = CryptoManager.encryptAesGcm(message.toByteArray(Charsets.UTF_8), symmetricKey)

        // Tamper with one byte of ciphertext
        val tamperedCiphertext = encResult.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        try {
            CryptoManager.decryptAesGcm(
                ciphertext = tamperedCiphertext,
                nonce = encResult.nonce,
                authTag = encResult.authTag,
                symmetricKey = symmetricKey
            )
            fail("Decryption must throw an exception when ciphertext has been tampered with")
        } catch (_: Exception) {
            // Expected security rejection
        }
    }

    @Test
    fun testMeshPacketSerializationRoundTrip() {
        val packet = MeshPacket(
            protocolVersion = 1,
            packetType = PacketType.MESSAGE,
            packetId = "pkt-12345",
            messageId = "msg-67890",
            conversationId = "conv-abc",
            senderDeviceId = "node-A",
            recipientDeviceId = "node-D",
            hopCount = 2,
            ttl = 8,
            ackType = AckType.DELIVERED,
            nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            encryptedPayload = "EncryptedBytes".toByteArray(Charsets.UTF_8),
            authTag = byteArrayOf(10, 20, 30, 40)
        )

        val serialized = packet.toByteArray()
        val deserialized = MeshPacket.fromByteArray(serialized)

        assertEquals(packet.protocolVersion, deserialized.protocolVersion)
        assertEquals(packet.packetType, deserialized.packetType)
        assertEquals(packet.packetId, deserialized.packetId)
        assertEquals(packet.messageId, deserialized.messageId)
        assertEquals(packet.conversationId, deserialized.conversationId)
        assertEquals(packet.senderDeviceId, deserialized.senderDeviceId)
        assertEquals(packet.recipientDeviceId, deserialized.recipientDeviceId)
        assertEquals(packet.hopCount, deserialized.hopCount)
        assertEquals(packet.ttl, deserialized.ttl)
        assertEquals(packet.ackType, deserialized.ackType)
        assertArrayEquals(packet.nonce, deserialized.nonce)
        assertArrayEquals(packet.encryptedPayload, deserialized.encryptedPayload)
        assertArrayEquals(packet.authTag, deserialized.authTag)
    }

    @Test
    fun testCriticalAcceptanceTestBluetoothHop() {
        // Critical Acceptance Test: A -> B -> C -> D on Bluetooth
        val result = MeshSimulationEngine.runMultiHopAcceptanceTest(
            transportMode = "BLUETOOTH",
            messageText = "Hello from A"
        )

        assertTrue("Scenario must succeed", result.success)
        assertEquals("Hello from A", result.receivedPlaintext)
        assertEquals("Must traverse 3 hops", 3, result.totalHops)
        assertTrue("Sender A must receive delivery ACK", result.ackReceivedBySender)

        // Intermediate nodes B & C inspection log must show encrypted blobs only
        for (log in result.intermediateNodeInspectLog) {
            assertTrue("Intermediate node must not see plaintext", log.contains("ENCRYPTED_BLOB"))
        }
    }

    @Test
    fun testCriticalAcceptanceTestWifiDirectHop() {
        // Critical Acceptance Test: A -> B -> C -> D on Wi-Fi Direct
        val result = MeshSimulationEngine.runMultiHopAcceptanceTest(
            transportMode = "WI_FI_DIRECT",
            messageText = "Hello from A"
        )

        assertTrue("Scenario must succeed on Wi-Fi Direct", result.success)
        assertEquals("Hello from A", result.receivedPlaintext)
        assertEquals(3, result.totalHops)
        assertTrue(result.ackReceivedBySender)
    }

    @Test
    fun testCriticalAcceptanceTestMixedTransportHop() {
        // Critical Acceptance Test: A ->(BT)-> B ->(WiFiDirect)-> C ->(BT)-> D
        val result = MeshSimulationEngine.runMultiHopAcceptanceTest(
            transportMode = "MIXED",
            messageText = "Hello from A"
        )

        assertTrue("Scenario must succeed on Mixed Transport", result.success)
        assertEquals("Hello from A", result.receivedPlaintext)
        assertEquals(3, result.totalHops)
        assertTrue(result.ackReceivedBySender)
    }

    @Test
    fun testNearbyPayloadFramingCompatibility() {
        val originalPacket = MeshPacket(
            protocolVersion = 1,
            packetType = PacketType.MESSAGE,
            packetId = "nearby-pkt-1",
            messageId = "nearby-msg-1",
            conversationId = "conv-nearby",
            senderDeviceId = "nearby_node_a",
            recipientDeviceId = "nearby_node_b",
            hopCount = 0,
            ttl = 10,
            nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            encryptedPayload = "NearbyPayload".toByteArray(Charsets.UTF_8),
            authTag = byteArrayOf(99, 88, 77, 66)
        )

        val payload = com.google.android.gms.nearby.connection.Payload.fromBytes(originalPacket.toByteArray())
        val receivedBytes = payload.asBytes()
        assertNotNull(receivedBytes)

        val reconstructedPacket = MeshPacket.fromByteArray(receivedBytes!!)
        assertEquals(originalPacket.packetId, reconstructedPacket.packetId)
        assertEquals(originalPacket.senderDeviceId, reconstructedPacket.senderDeviceId)
        assertEquals(originalPacket.recipientDeviceId, reconstructedPacket.recipientDeviceId)
        assertArrayEquals(originalPacket.encryptedPayload, reconstructedPacket.encryptedPayload)
        assertArrayEquals(originalPacket.nonce, reconstructedPacket.nonce)
        assertArrayEquals(originalPacket.authTag, reconstructedPacket.authTag)
    }

    @Test
    fun testNearbyConnectionManagerConfiguration() {
        assertEquals("Strategy must be P2P_CLUSTER for decentralized mesh topologies",
            com.google.android.gms.nearby.connection.Strategy.P2P_CLUSTER,
            com.example.transport.nearby.NearbyConnectionManager.STRATEGY
        )
        assertEquals("Service ID must match",
            "com.aistudio.meshchat.SERVICE_ID",
            com.example.transport.nearby.NearbyConnectionManager.DEFAULT_SERVICE_ID
        )

        val endpoint = com.example.transport.nearby.DiscoveredEndpoint(
            endpointId = "test-ep-1",
            endpointName = "Node Alpha",
            serviceId = com.example.transport.nearby.NearbyConnectionManager.DEFAULT_SERVICE_ID,
            medium = com.example.transport.nearby.NearbyMedium.HYBRID_AUTO
        )
        assertEquals("test-ep-1", endpoint.endpointId)
        assertEquals("Node Alpha", endpoint.endpointName)
        assertEquals(com.example.transport.nearby.NearbyMedium.HYBRID_AUTO, endpoint.medium)

        val connectedInfo = com.example.transport.nearby.ConnectionStateInfo(
            endpointId = "test-ep-1",
            endpointName = "Node Alpha",
            isConnected = true,
            state = com.example.transport.nearby.NearbyConnectionState.CONNECTED,
            statusMessage = "Connected over Bluetooth & Wi-Fi Direct",
            medium = com.example.transport.nearby.NearbyMedium.HYBRID_AUTO
        )
        assertTrue(connectedInfo.isConnected)
        assertEquals(com.example.transport.nearby.NearbyConnectionState.CONNECTED, connectedInfo.state)
        assertEquals(com.example.transport.nearby.NearbyMedium.HYBRID_AUTO, connectedInfo.medium)
    }

    @Test
    fun testMeshStressTest20Nodes() {
        val metrics = MeshSimulationEngine.runStressTest(20)
        assertEquals("PASSED", metrics["status"])
        val deliveryRate = metrics["deliveryRate"] as String
        assertTrue(deliveryRate.startsWith("100"))
    }
}
