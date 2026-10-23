package com.group16.dinoblog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.security.CanonicalSignatures
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.util.DeviceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase15IdentityTest {
    @Test fun signedRelayMessagePinsUnknownSourceOnlyAtFinalDestination(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val destination = DeviceIdentity(context).id
            val source = UUID.randomUUID().toString()
            val relay = UUID.randomUUID().toString()
            val verifier = CryptoIdentityManager(context, destination, db.identities())
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val keys = generator.generateKeyPair()
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.public.encoded)
            fun signed(to: String, payload: String, from: String = source): Message {
                val unsigned = Message(UUID.randomUUID().toString(), from, to, payload,
                    System.currentTimeMillis(), 60, MessageStatus.CARRIED, hopCount = 2,
                    signatureVersion = CanonicalSignatures.MESSAGE_VERSION,
                    originPublicKey = publicKey,
                    originPublicKeyFingerprint = CanonicalSignatures.sha256(keys.public.encoded),
                    contentDigest = CanonicalSignatures.sha256(payload.toByteArray(Charsets.UTF_8)))
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(keys.private)
                    update(CanonicalSignatures.message(unsigned))
                    Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
                }
                return unsigned.copy(originSignature = signature)
            }
            val forwarded = signed(destination, "A to C through B")
            assertEquals("UNKNOWN", verifier.verifyMessage(forwarded))
            assertEquals(null, db.identities().get(source))
            assertEquals("TOFU_TRUSTED", verifier.verifyMessage(forwarded, acceptFirstContact = true))
            assertEquals(publicKey, db.identities().get(source)?.publicKey)

            val anotherSource = UUID.randomUUID().toString()
            // A valid signature for a foreign destination must not create a local pin.
            assertEquals("UNKNOWN", verifier.verifyMessage(signed(relay, "relay only", anotherSource), acceptFirstContact = true))
            assertEquals(null, db.identities().get(anotherSource))
            val invalidSource = UUID.randomUUID().toString()
            val invalid = signed(destination, "valid").copy(sourceId = invalidSource)
            assertTrue(runCatching { verifier.verifyMessage(invalid, acceptFirstContact = true) }.isFailure)
            assertEquals(null, db.identities().get(invalidSource))
        } finally { db.close() }
    }

    @Test fun keystoreIdentityPersistsAndPinnedKeysCannotBeReplaced(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val id = DeviceIdentity(context).id
            val first = CryptoIdentityManager(context, id, db.identities())
            val fingerprint = first.fingerprint
            val second = CryptoIdentityManager(context, id, db.identities())
            assertEquals(fingerprint, second.fingerprint)
            assertEquals(first.publicKey, second.publicKey)
            val peer = UUID.randomUUID().toString()
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val keyOne = Base64.getUrlEncoder().withoutPadding().encodeToString(generator.generateKeyPair().public.encoded)
            val keyTwo = Base64.getUrlEncoder().withoutPadding().encodeToString(generator.generateKeyPair().public.encoded)
            assertEquals("TOFU_TRUSTED", first.pin(peer, keyOne, "TOFU"))
            assertEquals("KEY_MISMATCH", second.pin(peer, keyTwo, "TOFU"))
            assertEquals(keyOne, db.identities().get(peer)?.publicKey)
            val nonce = first.nonce()
            val proof = first.sign(CanonicalSignatures.hello(id, peer, nonce, "peer-challenge", fingerprint))
            assertTrue(second.verify(first.publicKey, CanonicalSignatures.hello(id, peer, nonce, "peer-challenge", fingerprint), proof))
            assertFalse(second.verify(first.publicKey, CanonicalSignatures.hello(id, peer, nonce, "fresh-challenge", fingerprint), proof))
        } finally { db.close() }
    }
}
