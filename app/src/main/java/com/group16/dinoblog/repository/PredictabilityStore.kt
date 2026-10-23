package com.group16.dinoblog.repository

import com.group16.dinoblog.model.*
import kotlinx.coroutines.flow.Flow

interface PredictabilityStore {
    val predictabilities: Flow<List<DeliveryPredictability>>
    suspend fun predictability(destination: String, at: Long): Double
    suspend fun recordEncounter(peer: String, at: Long): DeliveryPredictability
    suspend fun learnTransitive(localId: String, peer: String, entries: List<PredictabilityEntry>, at: Long)
    suspend fun predictabilitySummary(localId: String, relevant: Set<String>, at: Long): List<PredictabilityEntry>
    /** Aged local score evaluation and existing copy reservation share one transaction. */
    suspend fun reserveSmartSpray(id: String, localId: String, peer: String, expectedCopies: Int,
        peerScore: Double, at: Long): SprayReservation?
}
