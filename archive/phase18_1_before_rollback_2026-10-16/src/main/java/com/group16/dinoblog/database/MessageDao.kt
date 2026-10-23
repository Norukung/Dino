package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.group16.dinoblog.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // The primary key is the final deduplication guard, including simultaneous retries.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE messageId = :id")
    suspend fun get(id: String): Message?

    @Query("""UPDATE messages SET deliveryAttempted=1 WHERE messageId=:id AND deliveryAttempted=0
        AND NOT EXISTS (SELECT 1 FROM message_recalls WHERE messageId=:id)""")
    suspend fun markAttempted(id: String): Int

    @Query("DELETE FROM messages WHERE messageId=:id AND senderId!=:localId AND receiverId!=:localId")
    suspend fun deleteRelay(id: String, localId: String): Int

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, messageId ASC")
    fun observeAll(): Flow<List<Message>>

    @Query("UPDATE messages SET status = 'DELIVERED', reservedCopies = 0 WHERE messageId = :id AND status != 'RECEIVED'")
    suspend fun markReceiptDelivered(id: String): Int

    @Query("""UPDATE messages SET reservedCopies = :given, reservedPeerId = :peerId WHERE messageId = :id
        AND copiesRemaining = :expected AND copiesRemaining > 1 AND receiverId != :peerId AND receiverId != :localId
        AND reservedCopies = 0
        AND relayPolicy = 'ALLOW_RELAY' AND status IN ('PENDING','CARRIED','FORWARDED')
        AND expiresAt > :at AND hopCount < hopLimit
        AND NOT EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = :id)""")
    suspend fun reserveCopies(id: String, localId: String, peerId: String, expected: Int, given: Int, at: Long): Int

    @Query("""UPDATE messages SET reservedCopies = 0, reservedPeerId = NULL WHERE messageId = :id
        AND copiesRemaining = :kept + :given AND reservedCopies = :given
        AND status IN ('PENDING','CARRIED','FORWARDED')
        AND NOT EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = :id)""")
    suspend fun restoreCopies(id: String, kept: Int, given: Int): Int

    @Query("""UPDATE messages SET copiesRemaining = :kept, reservedCopies = 0, reservedPeerId = NULL,
        status = CASE WHEN status = 'PENDING' THEN 'FORWARDED' ELSE status END
        WHERE messageId = :id AND receiverId != :peerId AND hopCount = :previousHop
          AND copiesRemaining = :kept + :given AND reservedCopies = :given AND reservedPeerId = :peerId
          AND status IN ('PENDING','CARRIED','FORWARDED')""")
    suspend fun commitSpray(id: String, peerId: String, previousHop: Int, kept: Int, given: Int): Int

    @Query("""SELECT COUNT(*) FROM messages WHERE senderId != :localId AND receiverId != :localId
        AND status = 'CARRIED' AND expiresAt > :at
        AND NOT EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = messages.messageId)""")
    suspend fun relayBufferUsage(localId: String, at: Long): Int

    @Query("""DELETE FROM messages WHERE senderId != :localId AND receiverId != :localId
        AND status IN ('CARRIED','EXPIRED','DELIVERED','FORWARDED')
        AND (expiresAt <= :at OR hopCount >= hopLimit
             OR EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = messages.messageId))""")
    suspend fun reclaimRelayPayloads(localId: String, at: Long): Int

    @Query("""SELECT messageId, CASE
        WHEN EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = messages.messageId) THEN 'DELIVERED'
        WHEN expiresAt <= :at THEN 'EXPIRED' ELSE 'HOP_LIMIT' END AS reason
        FROM messages WHERE senderId != :localId AND receiverId != :localId
        AND status IN ('CARRIED','EXPIRED','DELIVERED','FORWARDED')
        AND (expiresAt <= :at OR hopCount >= hopLimit
             OR EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = messages.messageId))""")
    suspend fun relayCleanupCandidates(localId: String, at: Long): List<RelayCleanupCandidate>

    @Query("""SELECT DISTINCT receiverId FROM messages WHERE status IN ('PENDING','CARRIED','FORWARDED')
        AND expiresAt > :at AND NOT EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = messages.messageId)""")
    suspend fun routingDestinations(at: Long): List<String>

    // Original first-hop retry only. Phase 4 loads relay candidates by ID after inventory.
    @Query("SELECT * FROM messages WHERE senderId = :localId AND COALESCE(carrierId, receiverId) = :peerId AND status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun pending(localId: String, peerId: String): List<Message>

    @Query("UPDATE messages SET status = 'DELIVERED', reservedCopies = 0 WHERE messageId = :id AND senderId = :localId AND receiverId = :peerId AND carrierId IS NULL AND hopCount < hopLimit AND status IN ('PENDING', 'EXPIRED')")
    suspend fun markDelivered(id: String, localId: String, peerId: String): Int

    @Query("UPDATE messages SET status = 'FORWARDED' WHERE messageId = :id AND senderId = :localId AND carrierId = :peerId AND receiverId != :peerId AND hopCount < hopLimit AND status IN ('PENDING', 'EXPIRED')")
    suspend fun markForwarded(id: String, localId: String, peerId: String): Int

    // Called only for a matching, attempted current-session final-destination ACK.
    @Query("""UPDATE messages SET status = 'DELIVERED', reservedCopies = 0
        WHERE messageId = :id AND receiverId = :peerId AND receiverId != :localId
          AND hopCount = :previousHop AND hopCount >= 0 AND hopCount < hopLimit
          AND status IN ('PENDING', 'CARRIED', 'FORWARDED', 'EXPIRED')""")
    suspend fun markRelayDelivered(id: String, localId: String, peerId: String, previousHop: Int): Int

    // Relay ACK means storage, never final delivery. Keep each sender's path depth.
    @Query("""UPDATE messages SET status = CASE WHEN status = 'PENDING' THEN 'FORWARDED' ELSE status END
        WHERE messageId = :id AND receiverId != :peerId AND hopCount = :previousHop
          AND hopCount >= 0 AND hopCount < hopLimit
          AND status IN ('PENDING', 'CARRIED', 'FORWARDED', 'EXPIRED')""")
    suspend fun markReplicated(id: String, peerId: String, previousHop: Int): Int

    @Query("UPDATE messages SET status = 'EXPIRED', reservedCopies = 0, reservedPeerId = NULL WHERE status IN ('PENDING', 'CARRIED', 'FORWARDED') AND expiresAt <= :now")
    suspend fun expire(now: Long): Int

    // Only ID metadata leaves SQLite; payload is tested here, never loaded into an
    // inventory packet. The last permitted receiving hop equals the limit.
    @Query("""
        SELECT messageId, senderId AS sourceId, receiverId AS destinationId, carrierId
        FROM messages
        WHERE (status IN ('PENDING', 'CARRIED', 'FORWARDED')
               OR (:includeKnown AND status IN ('RECEIVED', 'DELIVERED')))
          AND expiresAt > :now AND hopCount >= 0 AND hopLimit BETWEEN 1 AND 255
          AND (hopCount < hopLimit OR (:includeKnown AND hopCount = hopLimit))
          AND copiesRemaining BETWEEN 1 AND 255
          AND ttl BETWEEN 1 AND 604800 AND timestamp > 0
          AND timestamp <= 9223372036854775807 - ttl * 1000
          AND timestamp <= :now + 300000 AND expiresAt = timestamp + ttl * 1000
          AND ((contentType = 'TEXT' AND length(payload) BETWEEN 1 AND 4096
                AND length(trim(payload, char(9,10,11,12,13,32))) > 0)
               OR (contentType IN ('IMAGE','AUDIO','VIDEO') AND mediaId IS NOT NULL AND EXISTS
                   (SELECT 1 FROM media WHERE media.mediaId = messages.mediaId
                    AND media.messageId = messages.messageId AND media.isComplete = 1
                    AND media.fileSize <= CASE messages.contentType
                        WHEN 'IMAGE' THEN :imageMax WHEN 'AUDIO' THEN :audioMax
                        WHEN 'VIDEO' THEN :videoMax ELSE 0 END)))
        ORDER BY messageId
    """)
    suspend fun inventoryCandidates(now: Long, includeKnown: Boolean = false,
        imageMax: Long = com.group16.dinoblog.model.MediaPolicy.IMAGE_MAX,
        audioMax: Long = com.group16.dinoblog.model.MediaPolicy.AUDIO_DTN_MAX,
        videoMax: Long = com.group16.dinoblog.model.MediaPolicy.VIDEO_DTN_MAX): List<InventoryCandidate>
}

/** Projection, not an entity: no Room schema/migration change. */
data class InventoryCandidate(val messageId: String, val sourceId: String,
    val destinationId: String, val carrierId: String?)

data class RelayCleanupCandidate(val messageId: String, val reason: String)
