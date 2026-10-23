package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.group16.dinoblog.model.OnlineMessageState
import kotlinx.coroutines.flow.Flow

@Dao
interface OnlineMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: OnlineMessageState)

    @Query("SELECT * FROM online_message_state WHERE messageId = :id")
    suspend fun get(id: String): OnlineMessageState?

    @Query("SELECT * FROM online_message_state")
    fun observeAll(): Flow<List<OnlineMessageState>>
}
