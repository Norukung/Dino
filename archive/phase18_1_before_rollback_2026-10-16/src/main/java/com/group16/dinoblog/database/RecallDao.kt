package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.group16.dinoblog.model.MessageRecall
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeResult
import kotlinx.coroutines.flow.Flow

@Dao
interface RecallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveState(row: MessageRecall)
    @Query("SELECT * FROM message_recalls WHERE messageId=:id") suspend fun state(id: String): MessageRecall?
    @Query("SELECT * FROM message_recalls") fun observeStates(): Flow<List<MessageRecall>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRevoke(row: Revoke): Long
    @Query("SELECT * FROM revokes WHERE originalMessageId=:id") suspend fun revoke(id: String): Revoke?
    @Query("SELECT * FROM revokes") fun observeRevokes(): Flow<List<Revoke>>
    @Query("SELECT * FROM revokes WHERE verificationState='VERIFIED' AND retentionUntil>:at ORDER BY originalMessageId") suspend fun active(at: Long): List<Revoke>
    @Query("SELECT * FROM revokes WHERE verificationState='PENDING_VERIFICATION' LIMIT 500") suspend fun pending(): List<Revoke>
    @Query("SELECT COUNT(*) FROM revokes WHERE originalSourceId!=:localId AND originalDestinationId!=:localId AND retentionUntil>:at")
    suspend fun activeRelayRevokeCount(localId: String, at: Long): Int
    @Query("UPDATE revokes SET verificationState='VERIFIED' WHERE revokeId=:id") suspend fun verify(id: String)
    @Query("DELETE FROM revokes WHERE revokeId=:id") suspend fun deleteRevoke(id: String)
    @Query("DELETE FROM revokes WHERE retentionUntil<=:at") suspend fun expire(at: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertResult(row: RevokeResult): Long
    @Query("SELECT * FROM revoke_results WHERE originalMessageId=:id") suspend fun result(id: String): RevokeResult?
    @Query("SELECT * FROM revoke_results") fun observeResults(): Flow<List<RevokeResult>>
    @Query("SELECT * FROM revoke_results ORDER BY originalMessageId") suspend fun results(): List<RevokeResult>
    @Query("SELECT * FROM revoke_results WHERE verificationState='PENDING_VERIFICATION' LIMIT 500") suspend fun pendingResults(): List<RevokeResult>
    @Query("SELECT COUNT(*) FROM revoke_results WHERE originalSourceId!=:localId AND originalDestinationId!=:localId")
    suspend fun relayResultCount(localId: String): Int
    @Query("UPDATE revoke_results SET verificationState='VERIFIED' WHERE resultId=:id") suspend fun verifyResult(id: String)
    @Query("DELETE FROM revoke_results WHERE resultId=:id") suspend fun deleteResult(id: String)
    @Query("DELETE FROM revoke_results WHERE originalSourceId!=:localId AND originalDestinationId!=:localId AND processedAt<:cutoff")
    suspend fun expireRelayResults(localId: String, cutoff: Long): Int
}
