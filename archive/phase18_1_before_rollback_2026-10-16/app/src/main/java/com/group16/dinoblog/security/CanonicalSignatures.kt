package com.group16.dinoblog.security

import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Version 1: consecutive unsigned 32-bit big-endian lengths and UTF-8 fields. */
object CanonicalSignatures {
    const val VERSION = 1
    const val MESSAGE_VERSION = 2
    fun fields(vararg values: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output -> values.forEach { value ->
            val encoded = value.toByteArray(Charsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        } }
    }.toByteArray()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun hello(ownId: String, peerId: String, ownNonce: String, peerNonce: String, fingerprint: String) =
        fields("DINOBLOG_HELLO_PROOF", VERSION.toString(), ownId, peerId, ownNonce, peerNonce, fingerprint)

    fun binding(deviceId: String, challenge: String, fingerprint: String) =
        fields("DINOBLOG_KEY_BINDING", VERSION.toString(), deviceId, challenge, fingerprint)

    fun registration(deviceId: String, challenge: String, fingerprint: String) =
        fields("DINOBLOG_DEVICE_REGISTRATION", VERSION.toString(), deviceId, challenge, fingerprint)

    fun message(message: Message, manifest: MediaManifest? = null): ByteArray {
        val version = message.signatureVersion ?: VERSION
        require(version == VERSION || version == MESSAGE_VERSION)
        require(version != VERSION || message.relayPolicy == RelayPolicy.ALLOW_RELAY)
        val isText = message.contentType == MessageContentType.TEXT
        val digest = if (isText) sha256(message.payload.toByteArray(Charsets.UTF_8))
            else manifest?.sha256 ?: requireNotNull(message.signedMediaSha256)
        val mime = manifest?.mimeType ?: message.signedMediaMimeType
        val size = manifest?.fileSize ?: message.signedMediaFileSize
        return fields("DINOBLOG_MESSAGE", version.toString(), message.messageId, message.sourceId,
            message.destinationId, message.contentType.name, message.createdAt.toString(),
            message.expiresAt.toString(), message.hopLimit.toString(),
            *if (version == MESSAGE_VERSION) arrayOf(message.relayPolicy.name) else emptyArray(), digest,
            if (isText) "" else requireNotNull(message.mediaId),
            if (isText) "" else requireNotNull(mime),
            if (isText) "0" else requireNotNull(size).toString(),
            if (isText) "" else digest)
    }

    fun receipt(receipt: DeliveryReceipt) = fields("DINOBLOG_DELIVERY_RECEIPT", VERSION.toString(),
        receipt.originalMessageId, receipt.sourceId, receipt.destinationId,
        receipt.createdByDeviceId, receipt.deliveredAt.toString(), receipt.originalExpiresAt.toString())

    fun revoke(revoke: Revoke) = fields("DINOBLOG_REVOKE", VERSION.toString(), revoke.revokeId,
        revoke.originalMessageId, revoke.originalSourceId, revoke.originalDestinationId,
        revoke.originalExpiresAt.toString(), revoke.createdAt.toString(), revoke.retentionUntil.toString())

    fun revokeResult(result: RevokeResult) = fields("DINOBLOG_REVOKE_RESULT", VERSION.toString(),
        result.resultId, result.revokeId, result.originalMessageId, result.originalSourceId,
        result.originalDestinationId, result.result.name, result.processedAt.toString())
}
