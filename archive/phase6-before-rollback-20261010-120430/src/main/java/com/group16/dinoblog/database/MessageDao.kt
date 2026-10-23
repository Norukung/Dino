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

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, messageId ASC")
    fun observeAll(): Flow<List<Message>>

    // Original first-hop retry only. Phase 4 loads relay candidates by ID after inventory.
    @Query("SELECT * FROM messages WHERE senderId = :localId AND COALESCE(carrierId, receiverId) = :peerId AND status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun pending(localId: String, peerId: String): List<Message>

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE messageId = :id AND senderId = :localId AND receiverId = :peerId AND carrierId IS NULL AND hopCount < hopLimit AND status IN ('PENDING', 'EXPIRED')")
    suspend fun markDelivered(id: String, localId: String, peerId: String): Int

    @Query("UPDATE messages SET status = 'FORWARDED' WHERE messageId = :id AND senderId = :localId AND carrierId = :peerId AND receiverId != :peerId AND hopCount < hopLimit AND status IN ('PENDING', 'EXPIRED')")
    suspend fun markForwarded(id: String, localId: String, peerId: String): Int

    // Called only for a matching, attempted current-session final-destination ACK.
    @Query("""UPDATE messages SET status = 'DELIVERED'
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

    @Query("UPDATE messages SET status = 'EXPIRED' WHERE status IN ('PENDING', 'CARRIED', 'FORWARDED') AND expiresAt <= :now")
    suspend fun expire(now: Long): Int

    @Query("""UPDATE messages SET status = 'DELIVERED' WHERE messageId = :id
        AND status IN ('PENDING', 'CARRIED', 'FORWARDED', 'EXPIRED')""")
    suspend fun markDeliveryKnown(id: String): Int

    // Only ID metadata leaves SQLite; payload is tested here, never loaded into an
    // inventory packet. The last permitted receiving hop equals the limit.
    @Query("""
        SELECT messageId, senderId AS sourceId, receiverId AS destinationId, carrierId
        FROM messages
        WHERE (status IN ('PENDING', 'CARRIED', 'FORWARDED')
               OR (:includeKnown AND status IN ('RECEIVED', 'DELIVERED')))
          AND expiresAt > :now AND hopCount >= 0 AND hopLimit BETWEEN 1 AND 255
          AND (hopCount < hopLimit OR (:includeKnown AND hopCount = hopLimit))
          AND ttl BETWEEN 1 AND 604800 AND timestamp > 0
          AND timestamp <= 9223372036854775807 - ttl * 1000
          AND timestamp <= :now + 300000 AND expiresAt = timestamp + ttl * 1000
          AND length(payload) BETWEEN 1 AND 4096
          AND length(trim(payload, char(9,10,11,12,13,32))) > 0
          AND NOT EXISTS (SELECT 1 FROM delivery_receipts r WHERE r.originalMessageId = messages.messageId)
        ORDER BY messageId
    """)
    suspend fun inventoryCandidates(now: Long, includeKnown: Boolean = false): List<InventoryCandidate>
}

/** Projection, not an entity: no Room schema/migration change. */
data class InventoryCandidate(val messageId: String, val sourceId: String,
    val destinationId: String, val carrierId: String?)
