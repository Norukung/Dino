package com.group16.dinoblog

import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeOutcome
import com.group16.dinoblog.model.RevokeResult
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.security.CanonicalSignatures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class Phase18RecallProtocolTest {
    private val source = "11111111-1111-4111-8111-111111111111"
    private val destination = "22222222-2222-4222-8222-222222222222"
    private val messageId = "33333333-3333-4333-8333-333333333333"
    private val revokeId = "44444444-4444-4444-8444-444444444444"
    private val resultId = "55555555-5555-4555-8555-555555555555"

    @Test fun `revoke and result cannot be changed after signing`() {
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(bytes); sign()
        }
        fun verifies(bytes: ByteArray, signature: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key.public); update(bytes); verify(signature)
        }
        val revoke = Revoke(revokeId, messageId, source, destination, 86_401_000, 1_000,
            172_801_000, 1, "a".repeat(100), "b".repeat(64), "c".repeat(60))
        val revokeSignature = sign(CanonicalSignatures.revoke(revoke))
        assertTrue(verifies(CanonicalSignatures.revoke(revoke), revokeSignature))
        assertFalse(verifies(CanonicalSignatures.revoke(revoke.copy(originalMessageId = resultId)), revokeSignature))
        assertFalse(verifies(CanonicalSignatures.revoke(revoke.copy(originalDestinationId = source)), revokeSignature))
        val result = RevokeResult(resultId, revokeId, messageId, source, destination,
            RevokeOutcome.CANCELLED_BEFORE_DELIVERY, 2_000, 1,
            "a".repeat(100), "b".repeat(64), "c".repeat(60))
        val resultSignature = sign(CanonicalSignatures.revokeResult(result))
        assertTrue(verifies(CanonicalSignatures.revokeResult(result), resultSignature))
        assertFalse(verifies(CanonicalSignatures.revokeResult(result.copy(result = RevokeOutcome.ALREADY_DELIVERED)),
            resultSignature))
    }

    @Test fun `same revoke travels in ordinary framed protocol`() {
        val revoke = Revoke(revokeId, messageId, source, destination, 86_401_000, 1_000,
            172_801_000, 1, "a".repeat(100), "b".repeat(64), "c".repeat(60))
        val packet = WirePacket(PacketType.REVOKE, revoke = revoke)
        val output = ByteArrayOutputStream()
        WireProtocol.write(DataOutputStream(output), packet)
        val decoded = WireProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertTrue(decoded.type == PacketType.REVOKE && decoded.revoke == revoke)
    }
}
