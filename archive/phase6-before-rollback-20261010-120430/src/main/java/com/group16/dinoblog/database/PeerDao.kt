package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.group16.dinoblog.model.Peer
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Upsert suspend fun save(peer: Peer)
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<Peer>>
}
