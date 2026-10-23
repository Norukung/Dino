package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.group16.dinoblog.model.ExperimentEvent
import com.group16.dinoblog.model.ExperimentMessageResult
import com.group16.dinoblog.model.ExperimentSession

@Dao
interface ExperimentDao {
    @Insert suspend fun insertSession(row: ExperimentSession)
    @Update suspend fun updateSession(row: ExperimentSession)
    @Query("SELECT * FROM experiment_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): ExperimentSession?
    @Query("SELECT * FROM experiment_sessions ORDER BY startedAt DESC")
    suspend fun sessions(): List<ExperimentSession>
    @Query("SELECT * FROM experiment_sessions WHERE experimentId = :id")
    suspend fun session(id: String): ExperimentSession?
    @Insert suspend fun insertEvent(row: ExperimentEvent): Long
    @Query("SELECT * FROM experiment_events WHERE experimentId = :id ORDER BY eventId")
    suspend fun events(id: String): List<ExperimentEvent>
    @Query("DELETE FROM experiment_events WHERE experimentId = :id AND eventId NOT IN (SELECT eventId FROM experiment_events WHERE experimentId = :id ORDER BY eventId DESC LIMIT :limit)")
    suspend fun pruneEvents(id: String, limit: Int)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMessage(row: ExperimentMessageResult)
    @Query("SELECT * FROM experiment_messages WHERE experimentId = :id AND messageId = :messageId")
    suspend fun message(id: String, messageId: String): ExperimentMessageResult?
    @Query("SELECT * FROM experiment_messages WHERE experimentId = :id ORDER BY createdAt, messageId")
    suspend fun messages(id: String): List<ExperimentMessageResult>
    @Query("DELETE FROM experiment_events WHERE experimentId = :id") suspend fun deleteEvents(id: String)
    @Query("DELETE FROM experiment_messages WHERE experimentId = :id") suspend fun deleteMessages(id: String)
    @Query("DELETE FROM experiment_sessions WHERE experimentId = :id") suspend fun deleteSession(id: String)
}
