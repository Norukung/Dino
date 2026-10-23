package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.model.RelayPolicyRules
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.security.CanonicalSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.group16.dinoblog.util.NetworkLogger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class Phase18_1RelayPolicyTest {
    private val source = "11111111-1111-4111-8111-111111111111"
    private val relay = "22222222-2222-4222-8222-222222222222"
    private val destination = "33333333-3333-4333-8333-333333333333"
    private val messageId = "44444444-4444-4444-8444-444444444444"
    private fun message(policy: RelayPolicy) = Message(messageId, source, destination, "Hi", 1000,
        60, MessageStatus.PENDING, copiesRemaining = 4, relayPolicy = policy)

    @Test fun policyIsInVersionTwoCanonicalBytesAndLegacyVersionOneRemainsValid() {
        val direct = message(RelayPolicy.DIRECT_ONLY).copy(signatureVersion = 2)
        assertEquals("35f7041b9589a15ea46886c6e7e4611a686d5dc434757430926b258fd9425579",
            CanonicalSignatures.sha256(CanonicalSignatures.message(direct)))
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keys = generator.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(CanonicalSignatures.message(direct)); sign()
        }
        fun valid(candidate: Message) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public); update(CanonicalSignatures.message(candidate)); verify(signature)
        }
        assertTrue(valid(direct))
        assertFalse(valid(direct.copy(relayPolicy = RelayPolicy.ALLOW_RELAY)))
        assertTrue(valid(direct.copy(hopCount = 1, copiesRemaining = 1)))
        val legacy = message(RelayPolicy.ALLOW_RELAY).copy(signatureVersion = 1)
        assertEquals(CanonicalSignatures.sha256(CanonicalSignatures.message(legacy)),
            CanonicalSignatures.sha256(CanonicalSignatures.message(legacy.copy(signatureVersion = null))))
        WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = direct))
        assertTrue(runCatching { WireProtocol.validate(WirePacket(PacketType.MESSAGE,
            message = direct.copy(signatureVersion = 1))) }.isFailure)
    }

    @Test fun senderAndCarrierConsentsStayIndependentAndDirectDestinationWins() {
        val allowed = message(RelayPolicy.ALLOW_RELAY)
        val direct = message(RelayPolicy.DIRECT_ONLY)
        assertEquals(ForwardingKind.SPRAY, DeliveryEngine.classify(allowed, source, relay, 2000))
        assertNull(DeliveryEngine.classify(direct, source, relay, 2000))
        assertEquals(ForwardingKind.DIRECT, DeliveryEngine.classify(direct, source, destination, 2000))
        assertNull(DeliveryEngine.classify(direct, relay, destination, 2000))
        assertTrue(RelayPolicyRules.mayAcceptFromPeer(direct, source, destination))
        assertFalse(RelayPolicyRules.mayAcceptFromPeer(direct, source, relay))
        assertFalse(RelayPolicyRules.mayAcceptFromPeer(direct, relay, destination))
        assertTrue(RelayPolicyRules.mayAcceptFromPeer(allowed, source, relay))
        assertFalse(DeliveryEngine.sameBundle(allowed, direct))
    }

    @Test fun receivingPeerRejectsThirdPartyDirectOnlyWithoutStoringOrAcknowledging() = runTest {
        class Wire(peer: String) : MessageTransport {
            override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "session"))
            override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
            val sent = mutableListOf<WirePacket>()
            override suspend fun send(peerId: String, packet: WirePacket): Boolean {
                sent += packet
                return true
            }
        }
        val direct = message(RelayPolicy.DIRECT_ONLY).copy(hopCount = 1)
        val relayStore = Phase4TestStore()
        val sourceWire = Wire(source)
        DeliveryEngine(relay, relayStore, sourceWire, backgroundScope, NetworkLogger(),
            now = { 2000 }).receive(source, WirePacket(PacketType.MESSAGE, message = direct), "session")
        assertTrue(relayStore.rows.isEmpty())
        assertEquals(PacketType.MESSAGE_REJECT, sourceWire.sent.single().type)
        assertEquals(com.group16.dinoblog.network.MessageRejectReason.RELAY_POLICY_DIRECT_ONLY,
            sourceWire.sent.single().reason)

        val destinationStore = Phase4TestStore()
        val maliciousWire = Wire(relay)
        DeliveryEngine(destination, destinationStore, maliciousWire, backgroundScope, NetworkLogger(),
            now = { 2000 }).receive(relay, WirePacket(PacketType.MESSAGE, message = direct), "session")
        assertTrue(destinationStore.rows.isEmpty())
        assertEquals(PacketType.MESSAGE_REJECT, maliciousWire.sent.single().type)

        val directWire = Wire(source)
        DeliveryEngine(destination, destinationStore, directWire, backgroundScope, NetworkLogger(),
            now = { 2000 }).receive(source, WirePacket(PacketType.MESSAGE, message = direct), "session")
        assertEquals(MessageStatus.RECEIVED, destinationStore.rows[messageId]?.status)
        assertEquals(PacketType.ACK, directWire.sent.single().type)
    }
}
