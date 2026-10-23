package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class RecallState { NONE, LOCAL_CANCELLED, REQUESTED, CONFIRMED_CANCELLED, ALREADY_DELIVERED }
@Serializable
enum class RevokeOutcome { CANCELLED_BEFORE_DELIVERY, ALREADY_DELIVERED }

@Entity(tableName = "message_recalls")
data class MessageRecall(@PrimaryKey val messageId: String, val state: String, val updatedAt: Long)

/** Immutable signed cancellation fact; verificationState is local metadata. */
@Serializable
@Entity(tableName = "revokes", indices = [Index(value = ["originalMessageId"], unique = true)])
data class Revoke(
    @PrimaryKey val revokeId: String,
    val originalMessageId: String,
    val originalSourceId: String,
    val originalDestinationId: String,
    val originalExpiresAt: Long,
    val createdAt: Long,
    val retentionUntil: Long,
    val signatureVersion: Int,
    val sourcePublicKey: String,
    val sourcePublicKeyFingerprint: String,
    val sourceSignature: String,
    @Transient val verificationState: String = "VERIFIED",
)

@Serializable
@Entity(tableName = "revoke_results", indices = [Index(value = ["originalMessageId"], unique = true)])
data class RevokeResult(
    @PrimaryKey val resultId: String,
    val revokeId: String,
    val originalMessageId: String,
    val originalSourceId: String,
    val originalDestinationId: String,
    val result: RevokeOutcome,
    val processedAt: Long,
    val signatureVersion: Int,
    val destinationPublicKey: String,
    val destinationPublicKeyFingerprint: String,
    val destinationSignature: String,
    @Transient val verificationState: String = "VERIFIED",
)
