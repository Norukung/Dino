package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.group16.dinoblog.model.PeerIdentityBinding
import com.group16.dinoblog.model.PendingSecurityReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerIdentityDao {
    @Query("SELECT * FROM peer_identity_bindings")
    fun observeAll(): Flow<List<PeerIdentityBinding>>
    @Query("SELECT * FROM peer_identity_bindings WHERE deviceId=:id")
    suspend fun get(id: String): PeerIdentityBinding?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(binding: PeerIdentityBinding)
    @Query("SELECT * FROM pending_security_receipts")
    suspend fun pending(): List<PendingSecurityReceipt>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePending(receipt: PendingSecurityReceipt)
    @Query("DELETE FROM pending_security_receipts WHERE originalMessageId=:id")
    suspend fun deletePending(id: String)
}
