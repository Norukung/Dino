package com.group16.dinoblog.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.group16.dinoblog.database.PeerIdentityDao
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.PeerIdentityBinding
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeResult
import com.group16.dinoblog.util.NetworkLogger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** The private key never leaves Android Keystore. Existing Device ID remains the routing identity. */
class CryptoIdentityManager(context: Context, val deviceId: String, private val bindings: PeerIdentityDao,
    private val logger: NetworkLogger? = null) {
    companion object { const val KEY_ALIAS = "dinoblog_identity_v1" }
    private val preferences = context.getSharedPreferences("cryptographic_identity", Context.MODE_PRIVATE)
    private val keyPair: KeyPair by lazy { loadOrCreate() }
    val publicKey: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)
    val fingerprint: String get() = CanonicalSignatures.sha256(keyPair.public.encoded)
    @Volatile var lastSecurityError: String? = null
        private set
    @Volatile var lastMessageSignatureVerified: Boolean? = null
        private set
    @Volatile var lastReceiptSignatureVerified: Boolean? = null
        private set
    fun noteFailure(reason: String) {
        lastSecurityError = reason.take(120)
        logger?.log("SECURITY", "SECURITY HELLO FAILED ${reason.take(120)}")
    }
    /** Installed by the transport coordinator; null means offline or no server binding. */
    var serverBinding: suspend (String) -> String? = { null }

    private fun loadOrCreate(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val remembered = preferences.getString("fingerprint", null)
        if (!store.containsAlias(KEY_ALIAS)) {
            check(remembered == null) { "Identity key missing. Explicit identity recovery is required." }
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
            generator.generateKeyPair()
        }
        val privateKey = requireNotNull(store.getKey(KEY_ALIAS, null) as? java.security.PrivateKey)
        val public = requireNotNull(store.getCertificate(KEY_ALIAS)).publicKey
        val actual = CanonicalSignatures.sha256(public.encoded)
        check(remembered == null || remembered == actual) { "Identity key changed. Explicit identity recovery is required." }
        if (remembered == null) check(preferences.edit().putString("fingerprint", actual).commit())
        logger?.log("SECURITY", "SECURITY IDENTITY READY fingerprint=$actual")
        return KeyPair(public, privateKey)
    }

    fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
    fun sign(bytes: ByteArray): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private); update(bytes)
        Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
    }
    fun verify(key: String, bytes: ByteArray, signature: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(decodePublicKey(key)); update(bytes)
            verify(Base64.getUrlDecoder().decode(signature))
        }
    }.getOrDefault(false)

    fun decodePublicKey(encoded: String): PublicKey {
        require(encoded.length in 100..512 && encoded.matches(Regex("[A-Za-z0-9_-]+")))
        val der = Base64.getUrlDecoder().decode(encoded)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        require(key is ECPublicKey && key.params.curve == expected.curve &&
            key.params.generator == expected.generator && key.params.order == expected.order &&
            key.params.cofactor == expected.cofactor && key.encoded.contentEquals(der)) { "Expected canonical P-256 public key" }
        return key
    }
    fun fingerprintOf(encoded: String): String = CanonicalSignatures.sha256(decodePublicKey(encoded).encoded)

    /** Existing pins are immutable. A server binding can upgrade TOFU only for the same key. */
    suspend fun pin(id: String, key: String, source: String): String {
        val fp = fingerprintOf(key)
        if (id == deviceId) require(fp == fingerprint) { "Local identity mismatch" }
        val old = bindings.get(id)
        if (old != null && old.fingerprint != fp) {
            bindings.save(old.copy(trustState = "KEY_MISMATCH"))
            lastSecurityError = "Security identity changed for $id"
            logger?.log("SECURITY", "SECURITY KEY MISMATCH deviceId=$id")
            return "KEY_MISMATCH"
        }
        val trust = if (source == "SERVER") "SERVER_VERIFIED" else old?.trustState?.takeIf { it == "SERVER_VERIFIED" } ?: "TOFU_TRUSTED"
        val now = System.currentTimeMillis()
        bindings.save(PeerIdentityBinding(id, key, fp, trust, old?.firstSeenAt ?: now, now, source))
        logger?.log("SECURITY", if (source == "SERVER") "SECURITY SERVER VERIFIED deviceId=$id" else "SECURITY KEY PINNED TOFU deviceId=$id")
        return trust
    }

    suspend fun trust(id: String, key: String): String {
        val fp = runCatching { fingerprintOf(key) }.getOrElse { return "INVALID" }
        if (id == deviceId) return if (fp == fingerprint) "SERVER_VERIFIED" else "KEY_MISMATCH"
        val old = bindings.get(id) ?: return "UNKNOWN"
        return if (old.fingerprint == fp && old.trustState != "KEY_MISMATCH") old.trustState else "KEY_MISMATCH"
    }

    suspend fun destinationBlocked(id: String): Boolean = bindings.get(id)?.trustState == "KEY_MISMATCH"

    private suspend fun verifiedTrust(id: String, key: String): String {
        if (id != deviceId && bindings.get(id)?.trustState != "SERVER_VERIFIED") {
            serverBinding(id)?.let { authoritative ->
                require(pin(id, authoritative, "SERVER") != "KEY_MISMATCH") { "IDENTITY_KEY_MISMATCH" }
            }
        }
        return trust(id, key)
    }

    fun signMessage(message: Message, manifest: MediaManifest? = null): Message {
        require(message.sourceId == deviceId)
        val digest = if (message.contentType == MessageContentType.TEXT)
            CanonicalSignatures.sha256(message.payload.toByteArray(Charsets.UTF_8)) else requireNotNull(manifest).sha256
        val signed = message.copy(signatureVersion = CanonicalSignatures.MESSAGE_VERSION, originPublicKey = publicKey,
            originPublicKeyFingerprint = fingerprint, contentDigest = digest,
            signedMediaMimeType = manifest?.mimeType, signedMediaFileSize = manifest?.fileSize,
            signedMediaSha256 = manifest?.sha256)
        return signed.copy(originSignature = sign(CanonicalSignatures.message(signed, manifest)))
    }

    suspend fun verifyMessage(message: Message, manifest: MediaManifest? = null,
        acceptFirstContact: Boolean = false): String = try {
        require(message.signatureVersion in setOf(1, CanonicalSignatures.MESSAGE_VERSION)) { "LEGACY_UNSIGNED_MESSAGE" }
        require(message.signatureVersion != 1 || message.relayPolicy == com.group16.dinoblog.model.RelayPolicy.ALLOW_RELAY) {
            "Invalid legacy relay policy"
        }
        val key = requireNotNull(message.originPublicKey) { "Missing origin key" }
        require(message.originPublicKeyFingerprint == fingerprintOf(key)) { "Message key fingerprint mismatch" }
        val expected = if (message.contentType == MessageContentType.TEXT)
            CanonicalSignatures.sha256(message.payload.toByteArray(Charsets.UTF_8)) else message.signedMediaSha256
        require(message.contentDigest == expected) { "Message content digest mismatch" }
        if (manifest != null) require(manifest.messageId == message.messageId && manifest.mediaId == message.mediaId &&
            manifest.mimeType == message.signedMediaMimeType && manifest.fileSize == message.signedMediaFileSize &&
            manifest.sha256 == message.signedMediaSha256 && manifest.mediaType == message.contentType) { "Signed media manifest mismatch" }
        require(verify(key, CanonicalSignatures.message(message), requireNotNull(message.originSignature))) { "Invalid origin signature" }
        val knownTrust = verifiedTrust(message.sourceId, key)
        // A relay can deliver a signed message before its source ever meets this phone.
        // Apply the same first-contact pinning used by HELLO only at the final receiver,
        // and only after the immutable message and signature have been verified.
        val trust = if (knownTrust == "UNKNOWN" && acceptFirstContact && message.destinationId == deviceId)
            pin(message.sourceId, key, "TOFU") else knownTrust
        require(trust != "KEY_MISMATCH" && trust != "INVALID") { "MESSAGE_AUTHENTICATION_FAILURE" }
        lastMessageSignatureVerified = true
        logger?.log("SECURITY", "SECURITY MESSAGE SIGNATURE VALID", message.messageId)
        trust
    } catch (error: Exception) {
        lastMessageSignatureVerified = false
        lastSecurityError = "Message authentication failed"
        logger?.log("SECURITY", "SECURITY MESSAGE SIGNATURE INVALID", message.messageId)
        throw error
    }

    fun signReceipt(receipt: DeliveryReceipt): DeliveryReceipt {
        require(receipt.destinationId == deviceId && receipt.createdByDeviceId == deviceId)
        val signed = receipt.copy(signatureVersion = 1, destinationPublicKey = publicKey,
            destinationPublicKeyFingerprint = fingerprint)
        return signed.copy(destinationSignature = sign(CanonicalSignatures.receipt(signed)))
    }

    suspend fun verifyReceipt(receipt: DeliveryReceipt): String = try {
        require(receipt.signatureVersion == 1) { "LEGACY_UNSIGNED_RECEIPT" }
        val key = requireNotNull(receipt.destinationPublicKey)
        require(receipt.destinationPublicKeyFingerprint == fingerprintOf(key)) { "Receipt key fingerprint mismatch" }
        require(verify(key, CanonicalSignatures.receipt(receipt), requireNotNull(receipt.destinationSignature))) { "Invalid receipt signature" }
        val trust = verifiedTrust(receipt.destinationId, key)
        require(trust != "KEY_MISMATCH" && trust != "INVALID") { "RECEIPT_AUTHENTICATION_FAILURE" }
        lastReceiptSignatureVerified = true
        logger?.log("SECURITY", "SECURITY RECEIPT SIGNATURE VALID", receipt.originalMessageId)
        trust
    } catch (error: Exception) {
        lastReceiptSignatureVerified = false
        lastSecurityError = "Delivery receipt authentication failed"
        logger?.log("SECURITY", "SECURITY RECEIPT SIGNATURE INVALID", receipt.originalMessageId)
        throw error
    }

    fun signRevoke(revoke: Revoke): Revoke {
        require(revoke.originalSourceId == deviceId && revoke.signatureVersion == 1)
        val signed = revoke.copy(sourcePublicKey = publicKey, sourcePublicKeyFingerprint = fingerprint)
        return signed.copy(sourceSignature = sign(CanonicalSignatures.revoke(signed)))
    }

    suspend fun verifyRevoke(revoke: Revoke): String {
        require(revoke.signatureVersion == 1 && revoke.sourcePublicKeyFingerprint == fingerprintOf(revoke.sourcePublicKey))
        require(verify(revoke.sourcePublicKey, CanonicalSignatures.revoke(revoke), revoke.sourceSignature)) { "Invalid REVOKE signature" }
        val trust = verifiedTrust(revoke.originalSourceId, revoke.sourcePublicKey)
        require(trust != "INVALID" && trust != "KEY_MISMATCH") { "REVOKE identity mismatch" }
        return trust
    }

    fun signRevokeResult(result: RevokeResult): RevokeResult {
        require(result.originalDestinationId == deviceId && result.signatureVersion == 1)
        val signed = result.copy(destinationPublicKey = publicKey, destinationPublicKeyFingerprint = fingerprint)
        return signed.copy(destinationSignature = sign(CanonicalSignatures.revokeResult(signed)))
    }

    suspend fun verifyRevokeResult(result: RevokeResult): String {
        require(result.signatureVersion == 1 && result.destinationPublicKeyFingerprint == fingerprintOf(result.destinationPublicKey))
        require(verify(result.destinationPublicKey, CanonicalSignatures.revokeResult(result), result.destinationSignature)) { "Invalid REVOKE_RESULT signature" }
        val trust = verifiedTrust(result.originalDestinationId, result.destinationPublicKey)
        require(trust != "INVALID" && trust != "KEY_MISMATCH") { "REVOKE_RESULT identity mismatch" }
        return trust
    }
}
