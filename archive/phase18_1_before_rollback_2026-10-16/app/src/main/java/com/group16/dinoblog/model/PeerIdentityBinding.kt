package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peer_identity_bindings")
data class PeerIdentityBinding(
    @PrimaryKey val deviceId: String,
    val publicKey: String,
    val fingerprint: String,
    val trustState: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
    val verificationSource: String,
)

@Entity(tableName = "pending_security_receipts")
data class PendingSecurityReceipt(
    @PrimaryKey val originalMessageId: String,
    val receiptJson: String,
    val receivedAt: Long,
)
