package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable-store stand-in shared by engine recreations; real Room is tested separately. */
internal class PredictabilityTestStore(val base: ReceiptTestStore = ReceiptTestStore()) :
    MessageStore by base, InventoryStore by base, ReceiptStore by base, PredictabilityStore {
    override val predictabilities = MutableStateFlow<List<DeliveryPredictability>>(emptyList())
    private val lock = Mutex()
    val encounters = mutableMapOf<String, Int>()
    var transitiveUpdates = 0
    var beforeSummary: suspend () -> Unit = {}
    private fun aged(id: String, at: Long): DeliveryPredictability? {
        val old = predictabilities.value.find { it.destinationDeviceId == id } ?: return null
        val next = old.copy(predictability = PredictabilityPolicy.age(old.predictability, old.lastUpdatedAt, at), lastUpdatedAt = maxOf(at, old.lastUpdatedAt))
        predictabilities.value = predictabilities.value.filterNot { it.destinationDeviceId == id } + next
        return next
    }
    suspend fun seed(id: String, score: Double, at: Long) = lock.withLock {
        predictabilities.value = predictabilities.value.filterNot { it.destinationDeviceId == id } + DeliveryPredictability(id, score, at)
    }
    override suspend fun predictability(destination: String, at: Long) = lock.withLock { aged(destination, at)?.predictability ?: 0.0 }
    override suspend fun recordEncounter(peer: String, at: Long) = lock.withLock {
        val old = aged(peer, at)
        val next = DeliveryPredictability(peer, PredictabilityPolicy.encounter(old?.predictability ?: 0.0), maxOf(at, old?.lastUpdatedAt ?: at))
        predictabilities.value = predictabilities.value.filterNot { it.destinationDeviceId == peer } + next
        encounters[peer] = (encounters[peer] ?: 0) + 1
        next
    }
    override suspend fun learnTransitive(localId: String, peer: String, entries: List<PredictabilityEntry>, at: Long) = lock.withLock {
        WireProtocol.validateSummary(entries)
        val toPeer = aged(peer, at)?.predictability ?: 0.0
        for (entry in entries) {
            if (entry.deviceId == localId || entry.deviceId == peer) continue
            val old = aged(entry.deviceId, at)
            val next = PredictabilityPolicy.transitive(old?.predictability ?: 0.0, toPeer, entry.predictability)
            if (next > (old?.predictability ?: 0.0)) predictabilities.value = predictabilities.value.filterNot { it.destinationDeviceId == entry.deviceId } +
                DeliveryPredictability(entry.deviceId, next, maxOf(at, old?.lastUpdatedAt ?: at))
        }
        transitiveUpdates += 1
    }
    override suspend fun predictabilitySummary(localId: String, relevant: Set<String>, at: Long): List<PredictabilityEntry> {
        beforeSummary()
        return lock.withLock { PredictabilityPolicy.summary(predictabilities.value, localId, relevant + base.rows.values.map { it.destinationId }, at) }
    }
    override suspend fun reserveSmartSpray(id: String, localId: String, peer: String, expectedCopies: Int, peerScore: Double, at: Long) = lock.withLock {
        val row = base.get(id)
        if (row == null || !PredictabilityPolicy.better(aged(row.destinationId, at)?.predictability ?: 0.0, peerScore)) null
        else base.reserveSpray(id, localId, peer, expectedCopies, at)
    }
}
