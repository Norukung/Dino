package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.group16.dinoblog.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: MediaEntity): Long

    @Update
    suspend fun update(row: MediaEntity): Int

    @Query("SELECT * FROM media WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): MediaEntity?

    @Query("SELECT * FROM media")
    suspend fun all(): List<MediaEntity>

    @Query("SELECT * FROM media ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("DELETE FROM media WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String): Int

    @Query("""SELECT COALESCE(SUM(fileSize), 0) FROM media
        WHERE sourceId != :localId AND destinationId != :localId
          AND expiresAt > :at
          AND NOT EXISTS (SELECT 1 FROM delivery_receipts WHERE originalMessageId = media.messageId)""")
    suspend fun relayBytes(localId: String, at: Long): Long

    @Query("""SELECT COUNT(*) FROM media WHERE sourceId != :localId AND destinationId != :localId
        AND isComplete = 0 AND expiresAt > :at""")
    suspend fun partialRelayCount(localId: String, at: Long): Int
}
