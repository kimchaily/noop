package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [StrainScorer] — HRmax helpers, Karvonen %HRR, Edwards zone
 * weights, the TRIMP→Effort log map, the full strain pipeline, percentile
 * interpolation, and observed-HRmax estimation.
 */
class StrainScorerTest {

    // ── HRmax helpers ──────────────────────────────────────────────────────

    @Test fun tanakaHRmax_knownValues() {
        // 208 - 0.7 * 30 = 208 - 21 = 187.0
        assertEquals(187.0, StrainScorer.tanakaHRmax(30.0), 0.001)
    }

    @Test fun defaultMaxHR_knownValues() {
        // 220 - 30 = 190
        assertEquals(190, StrainScorer.defaultMaxHR(30))
    }

    // ── Karvonen %HRR ──────────────────────────────────────────────────────

    @Test fun pctHRR_clamped() {
        // (50 - 60)/100*100 = -10 → clamped to 0.0 (below resting)
        assertEquals(0.0, StrainScorer.pctHRR(50.0, 60.0, 100.0), 0.001)
        // (200 - 60)/100*100 = 140 → clamped to 100.0
        assertEquals(100.0, StrainScorer.pctHRR(200.0, 60.0, 100.0), 0.001)
    }

    @Test fun pctHRR_midpoint() {
        // (110 - 60)/100*100 = 50.0
        assertEquals(50.0, StrainScorer.pctHRR(110.0, 60.0, 100.0), 0.001)
    }

    // ── Edwards zone weight ────────────────────────────────────────────────

    @Test fun zoneWeight_knownValues() {
        // pct = (50-60)/100*100 = -10 → no threshold met → zone 0
        assertEquals(0, StrainScorer.zoneWeight(50.0, 60.0, 100.0))
        // pct = (120-60)/100*100 = 60 → first threshold >= is 60.0 → zone 2
        assertEquals(2, StrainScorer.zoneWeight(120.0, 60.0, 100.0))
        // pct = (200-60)/100*100 = 140 → >= 90.0 → zone 5
        assertEquals(5, StrainScorer.zoneWeight(200.0, 60.0, 100.0))
    }

    // ── TRIMP → Effort log map ─────────────────────────────────────────────

    @Test fun trimpToStrain_zeroIsZero() {
        assertEquals(0.0, StrainScorer.trimpToStrain(0.0), 0.001)
    }

    @Test fun trimpToStrain_positiveInRange() {
        val score = StrainScorer.trimpToStrain(1000.0)
        assertTrue(score >= 0.0 && score <= 100.0)
    }

    @Test fun trimpToStrain_maxMapsTo100() {
        // TRIMP 7200 is the Edwards daily ceiling; ln(7201)/ln(7201) = 1 → 100.0
        assertEquals(100.0, StrainScorer.trimpToStrain(7200.0), 1.0)
    }

    // ── full strain pipeline ───────────────────────────────────────────────

    @Test fun strain_tooFewReadingsReturnsNull() {
        // 10 samples: below minSparseReadings (20) and minReadings (600) → null
        val hr = (0 until 10).map { HrSample("d", it.toLong(), 120) }
        assertNull(StrainScorer.strain(hr))
    }

    @Test fun strain_enoughReadingsProducesScore() {
        // 700 samples (>= minReadings 600) at varying HR above the zone-1 cutoff.
        val hr = (0 until 700).map { i ->
            HrSample(deviceId = "d", ts = i.toLong(), bpm = 160 + (i % 30))
        }
        val score = StrainScorer.strain(hr)
        assertNotNull(score)
        assertTrue(score!! >= 0.0 && score!! <= 100.0)
    }

    // ── percentile ─────────────────────────────────────────────────────────

    @Test fun percentile_knownVector() {
        // n=5, position = 0.5*(5-1) = 2.0 → sorted[2] = 3.0
        assertEquals(3.0, StrainScorer.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 50.0), 0.001)
    }

    // ── estimateHRmax ──────────────────────────────────────────────────────

    @Test fun estimateHRmax_tanakaFallback() {
        // Empty history, age 30 → tanaka(30) = 187.0, source "tanaka"
        val (hrmax, source) = StrainScorer.estimateHRmax(emptyList(), 30.0)
        assertEquals("tanaka", source)
        assertEquals(187.0, hrmax, 0.001)
    }

    @Test fun estimateHRmax_observed() {
        // 600 samples (>= hrmaxMinSamples) all at 200 bpm: observed 99.5th pctile
        // = 200.0 >= tanaka(30)=187.0 → (200.0, "observed")
        val history = List(600) { 200.0 }
        val (hrmax, source) = StrainScorer.estimateHRmax(history, 30.0)
        assertEquals("observed", source)
        assertEquals(200.0, hrmax, 0.001)
    }
}
