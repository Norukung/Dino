package com.group16.dinoblog.repository

import androidx.room.withTransaction
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.MessageRecall
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RecallState
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeOutcome
import com.group16.dinoblog.model.RevokeResult
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import java.util.UUID

class MessageRevokedException : IllegalStateException("MESSAGE_REVOKED")

/** Signed cancellation metadata lives beside delivery receipts in the existing Room store. */
class RecallRepository(private val db: DinoblogDatabase, private val localId: String,
    private val security: CryptoIdentityManager, private val media: MediaRepository,
    private val logger: NetworkLogger) {
    var onEvent: ((String, String, Long?, Long?) -> Unit)? = null
    private fun event(type: String, id: String, bytes: Long? = null, durationMs: Long? = null) =
        onEvent?.invoke(type, id, bytes, durationMs)
    private val dao = db.recalls()
    val states: Flow<List<MessageRecall>> = dao.observeStates()
    val revokes: Flow<List<Revoke>> = dao.observeRevokes()
    val results: Flow<List<RevokeResult>> = dao.observeResults()
    val changes: Flow<Unit> = combine(states, dao.observeRevokes(), dao.observeResults()) { _, _, _ -> Unit }

    suspend fun state(id: String) = dao.state(id)?.state?.let(RecallState::valueOf) ?: RecallState.NONE
    suspend fun revoke(id: String) = dao.revoke(id)?.takeIf { it.verificationState == "VERIFIED" && it.retentionUntil > System.currentTimeMillis() }
    suspend fun result(id: String) = dao.result(id)?.takeIf { it.verificationState == "VERIFIED" }
    suspend fun revokeIds() = dao.active(System.currentTimeMillis()).map { it.originalMessageId }
    suspend fun resultIds() = dao.results().filter { it.verificationState == "VERIFIED" }.map { it.originalMessageId }
    suspend fun revoked(id: String) = revoke(id) != null || state(id) == RecallState.LOCAL_CANCELLED

    /** Mark before an online request or peer frame. A simultaneous local cancel wins atomically. */
    suspend fun markAttempted(id: String): Boolean = db.withTransaction {
        if (dao.state(id) != null || dao.revoke(id)?.verificationState == "VERIFIED") return@withTransaction false
        val message = db.messages().get(id) ?: return@withTransaction false
        if (message.deliveryAttempted) return@withTransaction true
        db.messages().markAttempted(id) == 1
    }

    suspend fun cancelOwn(id: String): RecallState {
        val message = db.messages().get(id) ?: error("Message unavailable")
        require(message.sourceId == localId) { "Only the sender can cancel delivery" }
        dao.state(id)?.let { return RecallState.valueOf(it.state) }
        if (db.receipts().get(id) != null || message.status == MessageStatus.DELIVERED)
            return RecallState.ALREADY_DELIVERED
        require(message.expiresAt > System.currentTimeMillis()) { "Message has expired" }
        val at = System.currentTimeMillis()
        val unsigned = Revoke(UUID.randomUUID().toString(), id, localId, message.destinationId,
            message.expiresAt, at, message.expiresAt + WireProtocol.REVOKE_GRACE_MS,
            1, "", "", "")
        val signed = security.signRevoke(unsigned)
        val outcome = db.withTransaction {
            dao.state(id)?.let { return@withTransaction RecallState.valueOf(it.state) }
            if (db.receipts().get(id) != null) return@withTransaction RecallState.ALREADY_DELIVERED
            val online = db.onlineMessages().get(id)
            val current = db.messages().get(id) ?: error("Message unavailable")
            val localOnly = !current.deliveryAttempted && online == null &&
                current.status == MessageStatus.PENDING && current.reservedCopies == 0
            val state = if (localOnly) RecallState.LOCAL_CANCELLED else RecallState.REQUESTED
            if (!localOnly) check(dao.insertRevoke(signed) != -1L)
            dao.saveState(MessageRecall(id, state.name, at))
            state
        }
        logger.log("REVOKE", if (outcome == RecallState.LOCAL_CANCELLED) "MESSAGE LOCAL CANCEL" else "MESSAGE RECALL REQUESTED; REVOKE SIGNED", id)
        event(if (outcome == RecallState.LOCAL_CANCELLED) "LOCAL_CANCEL" else "RECALL_REQUESTED", id)
        return outcome
    }

    /** Unknown key bindings are retained inert until Phase 15 establishes trust. */
    suspend fun receiveRevoke(incoming: Revoke): Boolean {
        WireProtocol.validateRevoke(incoming)
        val receivedAt = System.currentTimeMillis()
        require(incoming.retentionUntil > receivedAt &&
            incoming.originalExpiresAt <= receivedAt + WireProtocol.MAX_TTL_SECONDS * 1_000L + WireProtocol.REVOKE_GRACE_MS) {
            "Expired or excessive REVOKE retention"
        }
        val old = dao.revoke(incoming.originalMessageId)
        if (old == null && localId != incoming.originalSourceId && localId != incoming.originalDestinationId)
            require(dao.activeRelayRevokeCount(localId, receivedAt) < 1_000) { "REVOKE control storage full" }
        require(old == null || old.copy(verificationState = incoming.verificationState) == incoming) { "REVOKE conflict" }
        val message = db.messages().get(incoming.originalMessageId)
        require(message == null || (message.sourceId == incoming.originalSourceId &&
            message.destinationId == incoming.originalDestinationId && message.expiresAt == incoming.originalExpiresAt)) { "REVOKE ownership conflict" }
        val trust = try { security.verifyRevoke(incoming) }
            catch (error: Exception) {
                event("REVOKE_INVALID_SIGNATURE", incoming.originalMessageId)
                logger.log("REVOKE", "REVOKE INVALID_SIGNATURE", incoming.originalMessageId)
                throw error
            }
        if (trust == "UNKNOWN") {
            if (old == null) dao.insertRevoke(incoming.copy(verificationState = "PENDING_VERIFICATION"))
            logger.log("REVOKE", "REVOKE PENDING_VERIFICATION", incoming.originalMessageId)
            event("REVOKE_PENDING_VERIFICATION", incoming.originalMessageId)
            return false
        }
        val at = System.currentTimeMillis()
        var newResult: RevokeResult? = null
        var relayRemoved = 0
        val relayTextBytes = message?.takeIf { it.sourceId != localId && it.destinationId != localId }
            ?.payload?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        db.withTransaction {
            if (old == null) dao.insertRevoke(incoming.copy(verificationState = "VERIFIED"))
            else dao.verify(old.revokeId)
            if (localId == incoming.originalDestinationId && dao.result(incoming.originalMessageId) == null) {
                val delivered = db.receipts().get(incoming.originalMessageId) != null ||
                    db.messages().get(incoming.originalMessageId)?.status == MessageStatus.RECEIVED
                val unsigned = RevokeResult(UUID.randomUUID().toString(), incoming.revokeId,
                    incoming.originalMessageId, incoming.originalSourceId, incoming.originalDestinationId,
                    if (delivered) RevokeOutcome.ALREADY_DELIVERED else RevokeOutcome.CANCELLED_BEFORE_DELIVERY,
                    at, 1, "", "", "")
                newResult = security.signRevokeResult(unsigned)
                dao.insertResult(requireNotNull(newResult))
            }
            if (localId == incoming.originalSourceId && dao.state(incoming.originalMessageId) == null)
                dao.saveState(MessageRecall(incoming.originalMessageId, RecallState.REQUESTED.name, at))
            relayRemoved = db.messages().deleteRelay(incoming.originalMessageId, localId)
        }
        val mediaBytes = media.cleanupRevoked(incoming.originalMessageId, localId)
        logger.log("REVOKE", "REVOKE VERIFIED; relayRows=$relayRemoved mediaBytes=$mediaBytes", incoming.originalMessageId)
        val reclaimed = mediaBytes + if (relayRemoved > 0) relayTextBytes else 0L
        if (reclaimed > 0) event("REVOKE_RELAY_RECLAIM", incoming.originalMessageId, reclaimed)
        if (newResult != null) event("REVOKE_PROPAGATION_DELAY", incoming.originalMessageId,
            durationMs = (at - incoming.createdAt).coerceAtLeast(0))
        newResult?.let { logger.log("REVOKE", "REVOKE RESULT ${it.result}", it.originalMessageId) }
        return true
    }

    suspend fun receiveResult(incoming: RevokeResult): Boolean {
        WireProtocol.validateRevokeResult(incoming)
        val old = dao.result(incoming.originalMessageId)
        if (old == null && localId != incoming.originalSourceId && localId != incoming.originalDestinationId)
            require(dao.relayResultCount(localId) < 1_000) { "REVOKE_RESULT control storage full" }
        require(old == null || old.copy(verificationState = incoming.verificationState) == incoming) { "REVOKE_RESULT conflict" }
        val revoke = dao.revoke(incoming.originalMessageId)
        require(revoke == null || (revoke.revokeId == incoming.revokeId &&
            revoke.originalSourceId == incoming.originalSourceId &&
            revoke.originalDestinationId == incoming.originalDestinationId)) { "REVOKE_RESULT identity conflict" }
        if (localId == incoming.originalSourceId) {
            require(revoke?.verificationState == "VERIFIED" && revoke.revokeId == incoming.revokeId &&
                dao.state(incoming.originalMessageId)?.state != RecallState.LOCAL_CANCELLED.name) {
                "REVOKE_RESULT has no local recall request"
            }
        }
        val trust = security.verifyRevokeResult(incoming)
        if (trust == "UNKNOWN") {
            if (old == null) dao.insertResult(incoming.copy(verificationState = "PENDING_VERIFICATION"))
            logger.log("REVOKE", "REVOKE_RESULT PENDING_VERIFICATION", incoming.originalMessageId)
            return false
        }
        db.withTransaction {
            if (old == null) dao.insertResult(incoming.copy(verificationState = "VERIFIED")) else dao.verifyResult(old.resultId)
            if (localId == incoming.originalSourceId && dao.state(incoming.originalMessageId) != null) {
                val delivered = db.receipts().get(incoming.originalMessageId) != null ||
                    incoming.result == RevokeOutcome.ALREADY_DELIVERED
                dao.saveState(MessageRecall(incoming.originalMessageId,
                    if (delivered) RecallState.ALREADY_DELIVERED.name else RecallState.CONFIRMED_CANCELLED.name,
                    System.currentTimeMillis()))
            }
        }
        logger.log("REVOKE", "REVOKE RESULT ${incoming.result}", incoming.originalMessageId)
        if (old?.verificationState != "VERIFIED")
            event(if (incoming.result == RevokeOutcome.ALREADY_DELIVERED) "REVOKE_ALREADY_DELIVERED" else "REVOKE_CONFIRMED",
                incoming.originalMessageId)
        return true
    }

    suspend fun revalidatePending(deviceId: String? = null) {
        for (row in dao.pending()) if (deviceId == null || row.originalSourceId == deviceId)
            runCatching { receiveRevoke(row) }.onFailure { dao.deleteRevoke(row.revokeId) }
        for (row in dao.pendingResults()) if (deviceId == null || row.originalDestinationId == deviceId)
            runCatching { receiveResult(row) }.onFailure { dao.deleteResult(row.resultId) }
    }

    suspend fun cleanup(at: Long) {
        dao.expire(at)
        dao.expireRelayResults(localId, at - 8L * WireProtocol.REVOKE_GRACE_MS)
        // A process may die after persisting the tombstone but before file cleanup.
        for (revoke in dao.active(at)) {
            val relayTextBytes = db.messages().get(revoke.originalMessageId)
                ?.takeIf { it.sourceId != localId && it.destinationId != localId }
                ?.payload?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            val removed = db.messages().deleteRelay(revoke.originalMessageId, localId)
            val bytes = media.cleanupRevoked(revoke.originalMessageId, localId)
            val reclaimed = bytes + if (removed > 0) relayTextBytes else 0L
            if (reclaimed > 0) event("REVOKE_RELAY_RECLAIM", revoke.originalMessageId, reclaimed)
        }
    }
}
