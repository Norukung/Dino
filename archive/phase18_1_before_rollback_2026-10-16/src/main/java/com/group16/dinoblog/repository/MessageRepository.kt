package com.group16.dinoblog.repository

import androidx.room.withTransaction
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.dtn.PredictabilityPolicy
import com.group16.dinoblog.model.DeliveryPredictability
import com.group16.dinoblog.model.PredictabilityEntry
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.database.InventoryCandidate
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.network.WireProtocol
import kotlinx.coroutines.flow.combine
import com.group16.dinoblog.util.NetworkLogger
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.model.PendingSecurityReceipt
import com.group16.dinoblog.model.MessageRecall
import com.group16.dinoblog.model.RecallState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MessageRepository(private val db: DinoblogDatabase, private val logger: NetworkLogger? = null,
    private val relayEnabled: () -> Boolean = { true },
    private val security: CryptoIdentityManager? = null) : MessageStore, InventoryStore, ReceiptStore, PredictabilityStore {
    private val securityJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override val predictabilities = db.predictabilities().observeAll()
    private suspend fun aged(destination: String, at: Long): DeliveryPredictability? {
        val old = db.predictabilities().get(destination) ?: return null
        val updated = old.copy(predictability = PredictabilityPolicy.age(old.predictability, old.lastUpdatedAt, at),
            lastUpdatedAt = maxOf(at, old.lastUpdatedAt))
        if (updated != old) {
            db.predictabilities().save(updated)
            logger?.log("ROUTE", "Predictability aged destination=$destination old=${old.predictability} new=${updated.predictability}")
        }
        return updated
    }
    override suspend fun predictability(destination: String, at: Long): Double = db.withTransaction { aged(destination, at)?.predictability ?: 0.0 }
    override suspend fun recordEncounter(peer: String, at: Long): DeliveryPredictability = db.withTransaction {
        require(WireProtocol.isUuid(peer) && at > 0)
        val old = aged(peer, at)
        val next = DeliveryPredictability(peer, PredictabilityPolicy.encounter(old?.predictability ?: 0.0), maxOf(at, old?.lastUpdatedAt ?: at))
        db.predictabilities().save(next)
        ageAndPrune(at)
        logger?.log("ROUTE", "Direct encounter peer=$peer old=${old?.predictability ?: 0.0} new=${next.predictability}")
        next
    }
    private suspend fun ageAndPrune(at: Long) {
        db.predictabilities().all().forEach { aged(it.destinationDeviceId, at) }
        db.predictabilities().prune(DtnConfig.MIN_PREDICTABILITY, DtnConfig.MAX_LOCAL_PREDICTABILITIES)
    }
    override suspend fun learnTransitive(localId: String, peer: String, entries: List<PredictabilityEntry>, at: Long) = db.withTransaction {
        WireProtocol.validateSummary(entries)
        val toPeer = aged(peer, at)?.predictability ?: 0.0
        for (entry in entries) {
            if (entry.deviceId == localId || entry.deviceId == peer) continue
            val old = aged(entry.deviceId, at)
            val next = PredictabilityPolicy.transitive(old?.predictability ?: 0.0, toPeer, entry.predictability)
            val candidate = toPeer * entry.predictability * DtnConfig.BETA
            logger?.log("ROUTE", "Transitive destination=${entry.deviceId} candidate=$candidate local=${old?.predictability ?: 0.0} accepted=${next > (old?.predictability ?: 0.0)}")
            if (next >= DtnConfig.MIN_PREDICTABILITY && next > (old?.predictability ?: 0.0))
                db.predictabilities().save(DeliveryPredictability(entry.deviceId, next, maxOf(at, old?.lastUpdatedAt ?: at)))
        }
        ageAndPrune(at)
    }
    override suspend fun predictabilitySummary(localId: String, relevant: Set<String>, at: Long): List<PredictabilityEntry> = db.withTransaction {
        ageAndPrune(at)
        PredictabilityPolicy.summary(db.predictabilities().all(), localId, relevant + db.messages().routingDestinations(at), at)
    }
    override suspend fun reserveSmartSpray(id: String, localId: String, peer: String, expectedCopies: Int, peerScore: Double, at: Long): SprayReservation? = db.withTransaction {
        val row = db.messages().get(id) ?: return@withTransaction null
        val localScore = aged(row.destinationId, at)?.predictability ?: 0.0
        if (!PredictabilityPolicy.better(localScore, peerScore)) return@withTransaction null
        reserveSpray(id, localId, peer, expectedCopies, at)
    }

    override val receipts = db.receipts().observeAll()
    override suspend fun receipt(id: String) = db.receipts().get(id)
    override suspend fun receiptIds() = db.receipts().ids()
    override suspend fun saveReceipt(receipt: DeliveryReceipt): Boolean {
        WireProtocol.validateReceipt(receipt)
        if (security != null) {
            val trust = security.verifyReceipt(receipt)
            if (trust == "UNKNOWN") {
                db.identities().savePending(PendingSecurityReceipt(receipt.originalMessageId,
                    securityJson.encodeToString(receipt), System.currentTimeMillis()))
                logger?.log("SECURITY", "RECEIPT PENDING_VERIFICATION", receipt.originalMessageId)
                return false
            }
        }
        return db.withTransaction {
            db.messages().get(receipt.originalMessageId)?.let {
                require(receipt.matches(it)) { "Receipt/message identity conflict" }
            }
            val existing = db.receipts().get(receipt.originalMessageId)
            require(existing == null || existing.copy(deliveredAt = receipt.deliveredAt) == receipt) { "Conflicting receipt identity" }
            val inserted = db.receipts().insert(receipt) != -1L
            db.messages().markReceiptDelivered(receipt.originalMessageId)
            if (db.recalls().state(receipt.originalMessageId) != null)
                db.recalls().saveState(MessageRecall(receipt.originalMessageId,
                    RecallState.ALREADY_DELIVERED.name, System.currentTimeMillis()))
            db.identities().deletePending(receipt.originalMessageId)
            inserted
        }
    }
    suspend fun revalidatePendingReceipts(destinationId: String? = null) {
        if (security == null) return
        for (pending in db.identities().pending()) {
            val receipt = runCatching { securityJson.decodeFromString<DeliveryReceipt>(pending.receiptJson) }.getOrNull()
            if (receipt != null && (destinationId == null || receipt.destinationId == destinationId))
                runCatching { saveReceipt(receipt) }
        }
    }
    override suspend fun commitReceived(message: Message, receipt: DeliveryReceipt?, localId: String, at: Long, relayLimit: Int): Boolean {
        if (security != null) {
            val trust = security.verifyMessage(message, acceptFirstContact = message.destinationId == localId)
            require(message.destinationId != localId || trust != "UNKNOWN") { "Source identity not verified" }
        }
        val signedReceipt = if (receipt != null && security != null) security.signReceipt(receipt) else receipt
        val accepted: Boolean? = db.withTransaction {
            if (db.recalls().revoke(message.messageId)?.verificationState == "VERIFIED" ||
                db.recalls().state(message.messageId)?.state == RecallState.LOCAL_CANCELLED.name)
                throw MessageRevokedException()
            signedReceipt?.let {
                require(localId == message.destinationId && it.createdByDeviceId == localId && it.matches(message))
                WireProtocol.validateReceipt(it)
            }
            val known = db.receipts().get(message.messageId)
            require(known == null || known.matches(message)) { "Receipt/message identity conflict" }
            val existing = db.messages().get(message.messageId)
            require(existing == null || DeliveryEngine.sameBundle(existing, message)) { "Conflicting duplicate" }
            if (message.contentType != MessageContentType.TEXT) {
                val media = db.media().forMessage(message.messageId)
                require(media != null && media.mediaId == message.mediaId &&
                    media.mediaType == message.contentType && media.isComplete &&
                    (security == null || (media.mimeType == message.signedMediaMimeType &&
                        media.fileSize == message.signedMediaFileSize && media.sha256 == message.signedMediaSha256))) { "Media is not verified" }
            }
            if (known != null) return@withTransaction false
            if (existing == null) {
                // Quota applies only to foreign relay payloads; source/final history is exempt.
                if (message.destinationId != localId && message.sourceId != localId) {
                    require(message.status == MessageStatus.CARRIED && relayLimit >= 0)
                    if (!relayEnabled()) throw RelayDisabledException()
                    val reclaimed = reclaimRelayPayloads(localId, at)
                    if (reclaimed > 0) logger?.log("DB", "Expired/delivered relay payloads reclaimed count=$reclaimed; tombstones retained")
                    logger?.log("DTN", "Relay buffer usage=${db.messages().relayBufferUsage(localId, at)}/$relayLimit", message.messageId)
                    if (db.messages().relayBufferUsage(localId, at) >= relayLimit) return@withTransaction null
                }
                check(db.messages().insert(message) != -1L) { "Message admission failed" }
            }
            if (signedReceipt != null) saveReceipt(signedReceipt)
            true
        }
        // Commit safe housekeeping even when valid active carriers still fill the quota.
        return accepted ?: throw RelayBufferFullException()
    }
    override val messages = db.messages().observeAll()
    override val peers = db.peers().observeAll()
    override suspend fun insert(message: Message) = db.withTransaction {
        if (db.receipts().get(message.messageId) != null ||
            db.recalls().revoke(message.messageId)?.verificationState == "VERIFIED") false
        else db.messages().insert(message) != -1L
    }
    override suspend fun get(id: String) = db.messages().get(id)
    override suspend fun pending(localId: String, peerId: String) = db.messages().pending(localId, peerId)
        .filterNot { db.recalls().state(it.messageId) != null || db.recalls().revoke(it.messageId)?.verificationState == "VERIFIED" }
    override suspend fun delivered(id: String, localId: String, peerId: String) =
        db.messages().markDelivered(id, localId, peerId) > 0
    override suspend fun forwarded(id: String, localId: String, peerId: String) =
        db.messages().markForwarded(id, localId, peerId) > 0
    override suspend fun relayDelivered(id: String, localId: String, peerId: String, previousHop: Int) =
        db.messages().markRelayDelivered(id, localId, peerId, previousHop) > 0
    override suspend fun replicated(id: String, peerId: String, previousHop: Int) =
        db.messages().markReplicated(id, peerId, previousHop) > 0
    override val inventoryChanges = combine(messages, receipts, db.media().observeAll(), db.recalls().observeStates()) { _, _, _, _ -> Unit }
    override suspend fun receivedMessageIds(now: Long) = validatedIds(db.messages().inventoryCandidates(now, includeKnown = true))
        .filterNot { db.recalls().revoke(it)?.verificationState == "VERIFIED" || db.recalls().state(it)?.state == RecallState.LOCAL_CANCELLED.name }
    override suspend fun reserveSpray(id: String, localId: String, peerId: String, expectedCopies: Int, at: Long): SprayReservation? = db.withTransaction {
        val row = db.messages().get(id) ?: return@withTransaction null
        if (row.copiesRemaining != expectedCopies || DeliveryEngine.classify(row, localId, peerId, at) != ForwardingKind.SPRAY) return@withTransaction null
        if (db.receipts().get(id) != null) return@withTransaction null
        if (db.recalls().revoke(id)?.verificationState == "VERIFIED" || db.recalls().state(id) != null) return@withTransaction null
        val give = row.copiesRemaining / 2
        val keep = row.copiesRemaining - give
        if (db.messages().reserveCopies(id, localId, peerId, expectedCopies, give, at) != 1) return@withTransaction null
        SprayReservation(row.copy(copiesRemaining = keep), give)
    }
    override suspend fun restoreSpray(id: String, expectedKept: Int, copiesGiven: Int): Boolean = db.withTransaction {
        require(expectedKept >= 1 && copiesGiven >= 1 && expectedKept + copiesGiven <= DtnConfig.MAX_SPRAY_COPIES)
        db.messages().restoreCopies(id, expectedKept, copiesGiven) == 1
    }
    override suspend fun confirmSpray(id: String, peerId: String, previousHop: Int, kept: Int, given: Int): Boolean = db.withTransaction {
        require(kept >= 1 && given >= 1 && kept + given <= DtnConfig.MAX_SPRAY_COPIES)
        db.messages().commitSpray(id, peerId, previousHop, kept, given) == 1
    }
    override suspend fun relayBufferUsage(localId: String, at: Long) = db.messages().relayBufferUsage(localId, at)
    override suspend fun expire(now: Long) { db.messages().expire(now) }
    override suspend fun cleanup(localId: String, now: Long): Int = db.withTransaction {
        db.messages().expire(now)
        reclaimRelayPayloads(localId, now)
    }
    private suspend fun reclaimRelayPayloads(localId: String, at: Long): Int {
        val candidates = db.messages().relayCleanupCandidates(localId, at)
        val reclaimed = db.messages().reclaimRelayPayloads(localId, at)
        if (reclaimed > 0) candidates.forEach {
            logger?.log("DTN", "DTN RELAY PAYLOAD RECLAIMED reason=${it.reason}", it.messageId)
        }
        return reclaimed
    }
    override suspend fun savePeer(peer: Peer) = db.peers().saveRemoteName(peer.deviceId, peer.name, peer.lastSeen)
    suspend fun setPeerAlias(deviceId: String, alias: String?) = db.withTransaction {
        db.peers().ensure(deviceId)
        db.peers().setAlias(deviceId, alias)
    }
    override suspend fun forwardableMessageIds(now: Long): List<String> {
        val delivered = receiptIds().toSet()
        return validatedIds(db.messages().inventoryCandidates(now)).filterNot {
            it in delivered || db.recalls().revoke(it)?.verificationState == "VERIFIED" || db.recalls().state(it) != null
        }
    }
    private fun validatedIds(candidates: List<InventoryCandidate>): List<String> = candidates.filter {
            WireProtocol.isUuid(it.messageId) && WireProtocol.isUuid(it.sourceId) &&
                WireProtocol.isUuid(it.destinationId) && it.sourceId != it.destinationId &&
                (it.carrierId == null || (WireProtocol.isUuid(it.carrierId) &&
                    it.carrierId != it.sourceId && it.carrierId != it.destinationId))
        }.map { it.messageId }
}
