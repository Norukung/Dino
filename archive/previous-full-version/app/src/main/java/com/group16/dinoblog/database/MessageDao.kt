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

    @Query("SELECT * FROM messages WHERE senderId = :localId AND receiverId = :peerId AND status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun pending(localId: String, peerId: String): List<Message>

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE messageId = :id AND senderId = :localId AND receiverId = :peerId AND status IN ('PENDING', 'EXPIRED')")
    suspend fun markDelivered(id: String, localId: String, peerId: String): Int

    @Query("UPDATE messages SET status = 'EXPIRED' WHERE status = 'PENDING' AND timestamp + ttl * 1000 <= :now")
    suspend fun expire(now: Long): Int
}
