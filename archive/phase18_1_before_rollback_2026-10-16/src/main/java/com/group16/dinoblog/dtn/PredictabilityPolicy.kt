package com.group16.dinoblog.dtn

import com.group16.dinoblog.model.*
import kotlin.math.pow

/** Pure, destination-specific advice layered on the Phase 7 validity/budget classifier. */
object PredictabilityPolicy {
    fun normalize(value: Double) = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
    fun age(value: Double, updatedAt: Long, at: Long): Double {
        val elapsed = if (at > updatedAt) at.toDouble() - updatedAt.toDouble() else 0.0
        return normalize(value) * DtnConfig.GAMMA.pow(elapsed / DtnConfig.AGING_INTERVAL_MS)
    }
    fun encounter(old: Double): Double {
        val safe = normalize(old)
        return normalize(safe + (1.0 - safe) * DtnConfig.P_INIT)
    }
    fun transitive(existing: Double, toPeer: Double, advertised: Double) =
        maxOf(normalize(existing), normalize(toPeer) * normalize(advertised) * DtnConfig.BETA)
    fun better(local: Double, peer: Double): Boolean = local.isFinite() && peer.isFinite() &&
        local in 0.0..1.0 && peer in 0.0..1.0 && peer - local + 1e-12 >= DtnConfig.FORWARDING_MARGIN
    fun decide(base: ForwardingKind?, local: Double, peer: Double, summaryReady: Boolean): ForwardingKind? = when (base) {
        ForwardingKind.DIRECT -> ForwardingKind.DIRECT
        ForwardingKind.SPRAY -> if (summaryReady && better(local, peer)) ForwardingKind.SMART_SPRAY else ForwardingKind.WAIT
        else -> base
    }
    fun summary(rows: List<DeliveryPredictability>, localId: String, relevant: Set<String>, at: Long): List<PredictabilityEntry> =
        rows.map { PredictabilityEntry(it.destinationDeviceId, age(it.predictability, it.lastUpdatedAt, at)) }
            .filter { it.deviceId != localId && it.predictability >= DtnConfig.MIN_PREDICTABILITY }
            .sortedWith(compareBy<PredictabilityEntry> { if (it.deviceId in relevant) 0 else 1 }
                .thenByDescending { it.predictability }.thenBy { it.deviceId })
            .take(DtnConfig.MAX_PREDICTABILITY_ENTRIES)
}
