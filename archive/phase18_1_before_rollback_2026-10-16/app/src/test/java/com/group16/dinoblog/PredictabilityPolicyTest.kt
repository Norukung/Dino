package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.pow

class PredictabilityPolicyTest {
    @Test fun directEncountersConvergeWithoutExceedingOne() {
        assertEquals(0.75, PredictabilityPolicy.encounter(0.0), 1e-12)
        assertEquals(0.9375, PredictabilityPolicy.encounter(0.75), 1e-12)
        var p = 0.0; repeat(100) { p = PredictabilityPolicy.encounter(p) }
        assertTrue(p in 0.0..1.0); assertEquals(1.0, p, 1e-10)
    }
    @Test fun lazyAgingIsCompositionalAndClockRollbackCannotIncreaseScores() {
        val t = 1000L; val hour = DtnConfig.AGING_INTERVAL_MS
        assertEquals(0.9 * 0.98.pow(48), PredictabilityPolicy.age(0.9, t, t + 48 * hour), 1e-12)
        val half = PredictabilityPolicy.age(0.9, t, t + hour / 2)
        assertEquals(PredictabilityPolicy.age(0.9, t, t + hour), PredictabilityPolicy.age(half, t + hour / 2, t + hour), 1e-12)
        assertEquals(0.9, PredictabilityPolicy.age(0.9, t, t - 1000), 0.0)
        assertTrue(PredictabilityPolicy.age(0.9, t, Long.MAX_VALUE) in 0.0..1.0)
    }
    @Test fun transitivityIsDiscountedAndNeverDowngradesStrongerLocalScore() {
        assertEquals(0.18, PredictabilityPolicy.transitive(0.05, 0.8, 0.9), 1e-12)
        assertEquals(0.7, PredictabilityPolicy.transitive(0.7, 0.8, 0.9), 0.0)
        assertNotEquals(0.9, PredictabilityPolicy.transitive(0.0, 0.8, 0.9), 1e-12)
    }
    @Test fun normalizationAndInvalidComparisonFailConservatively() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, PredictabilityPolicy.normalize(invalid), 0.0)
            assertFalse(PredictabilityPolicy.better(0.0, invalid))
        }
        assertEquals(0.0, PredictabilityPolicy.normalize(-1.0), 0.0)
        assertEquals(1.0, PredictabilityPolicy.normalize(4.5), 0.0)
        assertFalse(PredictabilityPolicy.better(0.2, 4.5)); assertFalse(PredictabilityPolicy.better(-1.0, 0.9))
    }
    @Test fun marginIsInclusiveAndUnknownAdviceNeverLooksExcellent() {
        assertTrue(PredictabilityPolicy.better(0.2, 0.8)); assertTrue(PredictabilityPolicy.better(0.2, 0.3))
        assertFalse(PredictabilityPolicy.better(0.4, 0.45)); assertFalse(PredictabilityPolicy.better(0.7, 0.2))
        assertFalse(PredictabilityPolicy.better(0.0, 0.0))
    }
    @Test fun directWaitAndSkipOverrideRelayAdvice() {
        assertEquals(ForwardingKind.DIRECT, PredictabilityPolicy.decide(ForwardingKind.DIRECT, 0.0, 0.0, false))
        assertEquals(ForwardingKind.WAIT, PredictabilityPolicy.decide(ForwardingKind.WAIT, 0.1, 0.95, true))
        assertNull(PredictabilityPolicy.decide(null, 0.0, 1.0, true))
        assertEquals(ForwardingKind.WAIT, PredictabilityPolicy.decide(ForwardingKind.SPRAY, 0.2, 0.8, false))
        assertEquals(ForwardingKind.SMART_SPRAY, PredictabilityPolicy.decide(ForwardingKind.SPRAY, 0.2, 0.8, true))
    }
    @Test fun summaryIsBoundedOmitsSelfAndNearZeroAndPrioritizesRelevantDestination() {
        val local = UUID.randomUUID().toString(); val relevant = UUID.randomUUID().toString()
        val rows = List(100) { DeliveryPredictability(UUID.randomUUID().toString(), 0.9, 1000) } +
            listOf(DeliveryPredictability(local, 1.0, 1000), DeliveryPredictability(relevant, 0.02, 1000),
                DeliveryPredictability(UUID.randomUUID().toString(), 0.0001, 1000))
        val summary = PredictabilityPolicy.summary(rows, local, setOf(relevant), 1000)
        assertEquals(DtnConfig.MAX_PREDICTABILITY_ENTRIES, summary.size)
        assertEquals(relevant, summary.first().deviceId)
        assertFalse(summary.any { it.deviceId == local || it.predictability < DtnConfig.MIN_PREDICTABILITY })
    }
}
