package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.group16.dinoblog.model.Peer
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Upsert suspend fun save(peer: Peer)
    @Transaction
    suspend fun saveRemoteName(deviceId: String, name: String, lastSeen: Long) {
        ensure(deviceId)
        updateRemoteName(deviceId, name, lastSeen)
    }
    @Query("UPDATE peers SET name = :name, lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updateRemoteName(deviceId: String, name: String, lastSeen: Long)
    @Query("INSERT OR IGNORE INTO peers (deviceId, name, lastSeen, localAlias) VALUES (:deviceId, 'DinoBlog User', 0, NULL)")
    suspend fun ensure(deviceId: String)
    @Query("UPDATE peers SET localAlias = :alias WHERE deviceId = :deviceId")
    suspend fun setAlias(deviceId: String, alias: String?)
    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): Peer?
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<Peer>>
}
