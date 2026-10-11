package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RecoveryScorer] — resting-HR floor, recovery bands, the
 * z-score + logistic composite "Charge" score, and the symmetric skin-temp
 * penalty. Verified against the documented constants in RecoveryScorer.kt.
 */
class RecoveryScorerTest {

    // ── restingHR ──────────────────────────────────────────────────────────

    @Test fun restingHR_emptyReturnsNull() {
        assertNull(RecoveryScorer.restingHR(emptyList(), 0L, 600L))
    }

    @Test fun restingHR_singleBin() {
        // 10 samples at 60 bpm all inside the first 5-min bin [0, 300).
        val hr = (0 until 10).map { HrSample(deviceId = "d", ts = it.toLong(), bpm = 60) }
        assertEquals(60, RecoveryScorer.restingHR(hr, 0L, 600L))
    }

    @Test fun restingHR_multipleBinsPicksMinimum() {
        // Bin [0,300): 10 samples @ 60. Bin [300,600): 10 samples @ 50. Min qualified = 50.
        val hr = (0 until 10).map { HrSample("d", it.toLong(), 60) } +
            (0 until 10).map { HrSample("d", 300L + it, 50) }
        assertEquals(50, RecoveryScorer.restingHR(hr, 0L, 600L))
    }

    @Test fun restingHR_rejectsArtifactBin() {
        // Bin [0,300): a single lone sample @ 20 bpm — under-populated (1 < 5) AND
        // sub-physiological (20 < 25), so it cannot win the floor. Bin [300,600):
        // 10 samples @ 60 qualifies and is the floor.
        val hr = listOf(HrSample("d", 0L, 20)) +
            (0 until 10).map { HrSample("d", 300L + it, 60) }
        assertEquals(60, RecoveryScorer.restingHR(hr, 0L, 600L))
    }

    // ── band ───────────────────────────────────────────────────────────────

    @Test fun band_redBelow34() {
        assertEquals("red", RecoveryScorer.band(20.0))
    }

    @Test fun band_yellow34to67() {
        assertEquals("yellow", RecoveryScorer.band(50.0))
    }

    @Test fun band_green67AndAbove() {
        assertEquals("green", RecoveryScorer.band(80.0))
    }

    // ── recovery ───────────────────────────────────────────────────────────

    @Test fun recovery_coldStartReturnsNull() {
        val base = RecoveryScorer.DriverBaseline(mean = 50.0, spread = 10.0)
        val score = RecoveryScorer.recovery(
            hrv = 50.0, rhr = 60.0, resp = null,
            hrvBaseline = base, rhrBaseline = null, respBaseline = null,
            sleepPerf = null, hrvBaselineUsable = false,
        )
        assertNull(score)
    }

    @Test fun recovery_hrvOnlyProducesScore() {
        val base = RecoveryScorer.DriverBaseline(mean = 50.0, spread = 10.0)
        val score = RecoveryScorer.recovery(
            hrv = 55.0, rhr = 60.0, resp = null,
            hrvBaseline = base, rhrBaseline = null, respBaseline = null,
            sleepPerf = null,
        )
        assertNotNull(score)
        assertTrue(score!! >= 0.0 && score!! <= 100.0)
    }

    @Test fun recovery_allDriversProduceScore() {
        val hrvBase = RecoveryScorer.DriverBaseline(mean = 50.0, spread = 10.0)
        val rhrBase = RecoveryScorer.DriverBaseline(mean = 60.0, spread = 5.0)
        val respBase = RecoveryScorer.DriverBaseline(mean = 14.0, spread = 2.0)
        val score = RecoveryScorer.recovery(
            hrv = 55.0, rhr = 58.0, resp = 13.5,
            hrvBaseline = hrvBase, rhrBaseline = rhrBase, respBaseline = respBase,
            sleepPerf = 0.90, skinTempDev = 0.2,
        )
        assertNotNull(score)
        assertTrue(score!! >= 0.0 && score!! <= 100.0)
    }

    @Test fun recovery_higherHrvHigherScore() {
        val base = RecoveryScorer.DriverBaseline(mean = 50.0, spread = 10.0)
        val low = RecoveryScorer.recovery(
            hrv = 50.0, rhr = 60.0, resp = null,
            hrvBaseline = base, rhrBaseline = null, respBaseline = null,
            sleepPerf = null,
        )
        val high = RecoveryScorer.recovery(
            hrv = 70.0, rhr = 60.0, resp = null,
            hrvBaseline = base, rhrBaseline = null, respBaseline = null,
            sleepPerf = null,
        )
        assertNotNull(low)
        assertNotNull(high)
        assertTrue("higher HRV should lift the score", high!! > low!!)
    }

    @Test fun recovery_skinTempSymmetricPenalty() {
        // |dev| = 1.0 in either direction must yield the same score: the term is
        // −|skinTempDev| / skinTempDevScale, sign-agnostic.
        val hrvBase = RecoveryScorer.DriverBaseline(mean = 50.0, spread = 10.0)
        val rhrBase = RecoveryScorer.DriverBaseline(mean = 60.0, spread = 5.0)
        val up = RecoveryScorer.recovery(
            hrv = 55.0, rhr = 58.0, resp = null,
            hrvBaseline = hrvBase, rhrBaseline = rhrBase, respBaseline = null,
            sleepPerf = 0.90, skinTempDev = 1.0,
        )
        val down = RecoveryScorer.recovery(
            hrv = 55.0, rhr = 58.0, resp = null,
            hrvBaseline = hrvBase, rhrBaseline = rhrBase, respBaseline = null,
            sleepPerf = 0.90, skinTempDev = -1.0,
        )
        assertNotNull(up)
        assertNotNull(down)
        assertEquals(up!!, down!!, 1e-6)
    }

    // ── zScore ─────────────────────────────────────────────────────────────

    @Test fun zScore_basicCalculation() {
        // (100 - 90) / (1.253 * 10) = 10 / 12.53 ≈ 0.798
        assertEquals(0.798, RecoveryScorer.zScore(100.0, 90.0, 10.0), 0.001)
    }
}
