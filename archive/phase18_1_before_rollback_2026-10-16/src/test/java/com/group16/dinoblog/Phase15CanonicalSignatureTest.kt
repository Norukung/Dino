package com.group16.dinoblog

import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.security.CanonicalSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class Phase15CanonicalSignatureTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val d = "22222222-2222-4222-8222-222222222222"
    private val id = "33333333-3333-4333-8333-333333333333"
    private val mediaId = "44444444-4444-4444-8444-444444444444"
    private val digest = "a".repeat(64)

    @Test fun `canonical version one matches server test vectors`() {
        val hello = CanonicalSignatures.hello(a, d, "AAABBB", "CCCDDD", digest)
        assertEquals("1e59cda72943940297e2e67f939540cf5f7cade076c7442156b59b39d3a25de4", CanonicalSignatures.sha256(hello))
        assertEquals("dd3077f09c9c220c2795eac442bb566ad690c4f52c44e89fc607acabc3af4ae8",
            CanonicalSignatures.sha256(CanonicalSignatures.registration(a, "AAABBB", digest)))
        val text = Message(id, a, d, "Hi", 1000, 60, MessageStatus.PENDING)
        assertEquals("4c499f3bf69e8a20de475c93d40b37e1d2b8b879537d05cb758bd32f201bb16a",
            CanonicalSignatures.sha256(CanonicalSignatures.message(text)))
        val image = text.copy(payload = "", contentType = MessageContentType.IMAGE, mediaId = mediaId)
        val manifest = MediaManifest(id, mediaId, "image/jpeg", "image.jpg", 123, digest, 131072, 1)
        assertEquals("86a01db9a9c6666e2e35232a70c378a1c3f4e32917e171f68899f012e0316e2b",
            CanonicalSignatures.sha256(CanonicalSignatures.message(image, manifest)))
        val receipt = DeliveryReceipt(id, a, d, 2000, 61000, d)
        assertEquals("74344c0e23cacbc00bc2bffcb232a3fa897deefaf49ac36a37f157b55eb99b4e",
            CanonicalSignatures.sha256(CanonicalSignatures.receipt(receipt)))
    }

    @Test fun `fresh peer challenge and immutable content are signed but routing fields are not`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val original = Message(id, a, d, "Hi", 1000, 60, MessageStatus.PENDING)
        val bytes = CanonicalSignatures.message(original)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private); signer.update(bytes)
        val signature = signer.sign()
        fun valid(candidate: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public); update(candidate); verify(signature)
        }
        assertTrue(valid(CanonicalSignatures.message(original.copy(hopCount = 2, copiesRemaining = 1,
            status = MessageStatus.CARRIED))))
        assertFalse(valid(CanonicalSignatures.message(original.copy(payload = "Hi!"))))
        assertFalse(valid(CanonicalSignatures.message(original.copy(destinationId = a))))
        val proof = CanonicalSignatures.hello(a, d, "AAABBB", "CCCDDD", digest)
        val proofSigner = Signature.getInstance("SHA256withECDSA")
        proofSigner.initSign(pair.private); proofSigner.update(proof)
        val oldProofSignature = proofSigner.sign()
        val replayVerifier = Signature.getInstance("SHA256withECDSA")
        replayVerifier.initVerify(pair.public)
        replayVerifier.update(CanonicalSignatures.hello(a, d, "AAABBB", "FRESHNONCE", digest))
        assertFalse(replayVerifier.verify(oldProofSignature))
    }

    @Test fun `media metadata and destination receipts reject substitution`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val destination = generator.generateKeyPair()
        val attacker = generator.generateKeyPair()
        fun signed(bytes: ByteArray, privateKey: java.security.PrivateKey): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(bytes); sign() }
        fun verified(bytes: ByteArray, signature: ByteArray, publicKey: java.security.PublicKey): Boolean =
            Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(bytes); verify(signature) }
        val base = Message(id, a, d, "", 1000, 60, MessageStatus.PENDING,
            contentType = MessageContentType.IMAGE, mediaId = mediaId)
        for ((type, mime) in listOf(MessageContentType.IMAGE to "image/jpeg",
            MessageContentType.AUDIO to "audio/mp4", MessageContentType.VIDEO to "video/mp4")) {
            val message = base.copy(contentType = type)
            val manifest = MediaManifest(id, mediaId, mime, "media", 123, digest, 131072, 1, type)
            val signature = signed(CanonicalSignatures.message(message, manifest), destination.private)
            assertTrue(verified(CanonicalSignatures.message(message, manifest), signature, destination.public))
            assertFalse(verified(CanonicalSignatures.message(message, manifest.copy(sha256 = "b".repeat(64))),
                signature, destination.public))
            assertFalse(verified(CanonicalSignatures.message(message, manifest.copy(mimeType = "changed/type")),
                signature, destination.public))
        }
        val receipt = DeliveryReceipt(id, a, d, 2000, 61000, d)
        val receiptBytes = CanonicalSignatures.receipt(receipt)
        val good = signed(receiptBytes, destination.private)
        val forged = signed(receiptBytes, attacker.private)
        assertTrue(verified(receiptBytes, good, destination.public))
        assertFalse(verified(receiptBytes, forged, destination.public))
        assertFalse(verified(CanonicalSignatures.receipt(receipt.copy(destinationId = a)), good, destination.public))
    }
}
