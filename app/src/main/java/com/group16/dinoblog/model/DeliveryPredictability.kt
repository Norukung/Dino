package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Local destination estimate, not a peer's table or delivery proof. */
@Entity(tableName = "delivery_predictabilities")
data class DeliveryPredictability(
    @PrimaryKey val destinationDeviceId: String,
    val predictability: Double,
    val lastUpdatedAt: Long,
)

@Serializable
data class PredictabilityEntry(val deviceId: String, val predictability: Double)
