package com.group16.dinoblog.database

import androidx.room.*
import com.group16.dinoblog.model.DeliveryPredictability
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryPredictabilityDao {
    @Query("SELECT * FROM delivery_predictabilities WHERE destinationDeviceId = :id")
    suspend fun get(id: String): DeliveryPredictability?
    @Query("SELECT * FROM delivery_predictabilities ORDER BY predictability DESC, destinationDeviceId")
    suspend fun all(): List<DeliveryPredictability>
    @Query("SELECT * FROM delivery_predictabilities ORDER BY predictability DESC, destinationDeviceId")
    fun observeAll(): Flow<List<DeliveryPredictability>>
    @Upsert
    suspend fun save(entry: DeliveryPredictability)
    @Query("""DELETE FROM delivery_predictabilities WHERE predictability < :minimum OR destinationDeviceId NOT IN
        (SELECT destinationDeviceId FROM delivery_predictabilities ORDER BY predictability DESC, lastUpdatedAt DESC, destinationDeviceId LIMIT :limit)""")
    suspend fun prune(minimum: Double, limit: Int)
}
