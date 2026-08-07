package com.noop.analytics

import com.noop.data.RrInterval
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [HrvAnalyzer] — RMSSD, SDNN, pNN50, and the RR cleaning pipeline.
 * Verified against Task Force (1996) definitions and known HRV test vectors.
 */
class HrvAnalyzerTest {

    // ── rmssdRaw ───────────────────────────────────────────────────────────

    @Test fun rmssdRaw_emptyReturnsNull() {
        assertNull(HrvAnalyzer.rmssdRaw(emptyList()))
    }

    @Test fun rmssdRaw_singleValueReturnsNull() {
        assertNull(HrvAnalyzer.rmssdRaw(listOf(800.0)))
    }

    @Test fun rmssdRaw_twoValues() {
        // RMSSD of [800, 850] = sqrt(((850-800)^2) / 1) = 50.0
        val result = HrvAnalyzer.rmssdRaw(listOf(800.0, 850.0))
        assertEquals(50.0, result!!, 0.001)
    }

    @Test fun rmssdRaw_knownVector() {
        // NN = [800, 810, 790, 805, 795]
        // Successive diffs: [10, -20, 15, -10], squared: [100, 400, 225, 100], sum=825
        // RMSSD = sqrt(825/4) = sqrt(206.25) ≈ 14.364
        val nn = listOf(800.0, 810.0, 790.0, 805.0, 795.0)
        val result = HrvAnalyzer.rmssdRaw(nn)
        assertEquals(14.364, result!!, 0.01)
    }

    @Test fun rmssdRaw_constantSeries() {
        // All identical → all diffs = 0 → RMSSD = 0
        val result = HrvAnalyzer.rmssdRaw(listOf(800.0, 800.0, 800.0, 800.0))
        assertEquals(0.0, result!!, 0.001)
    }

    // ── sdnnRaw ────────────────────────────────────────────────────────────

    @Test fun sdnnRaw_emptyReturnsNull() {
        assertNull(HrvAnalyzer.sdnnRaw(emptyList()))
    }

    @Test fun sdnnRaw_singleValueReturnsNull() {
        assertNull(HrvAnalyzer.sdnnRaw(listOf(800.0)))
    }

    @Test fun sdnnRaw_knownVector() {
        // NN = [800, 810, 790, 805, 795], mean = 800
        // Deviations: [0, 10, -10, 5, -5], squared: [0, 100, 100, 25, 25], sum = 250
        // SDNN = sqrt(250/4) = sqrt(62.5) ≈ 7.906
        val nn = listOf(800.0, 810.0, 790.0, 805.0, 795.0)
        val result = HrvAnalyzer.sdnnRaw(nn)
        assertEquals(7.906, result!!, 0.01)
    }

    @Test fun sdnnRaw_constantSeries() {
        val result = HrvAnalyzer.sdnnRaw(listOf(800.0, 800.0, 800.0))
        assertEquals(0.0, result!!, 0.001)
    }

    // ── rangeFilter ────────────────────────────────────────────────────────

    @Test fun rangeFilter_dropsOutOfRange() {
        val rr = listOf(200.0, 400.0, 800.0, 1500.0, 2500.0)
        val filtered = HrvAnalyzer.rangeFilter(rr)
        assertEquals(3, filtered.size)
        assertEquals(400.0, filtered[0], 0.001)
        assertEquals(800.0, filtered[1], 0.001)
        assertEquals(1500.0, filtered[2], 0.001)
    }

    @Test fun rangeFilter_keepsBoundaryValues() {
        val rr = listOf(300.0, 2000.0)
        val filtered = HrvAnalyzer.rangeFilter(rr)
        assertEquals(2, filtered.size)
    }

    @Test fun rangeFilter_emptyInput() {
        assertTrue(HrvAnalyzer.rangeFilter(emptyList()).isEmpty())
    }

    // ── rejectEctopic ──────────────────────────────────────────────────────

    @Test fun rejectEctopic_dropsOutlier() {
        // Series: [800, 800, 1200, 800, 800] — 1200 is an ectopic beat
        // With a 5-beat window, the median of [800, 800, 800, 800] is 800
        // 1200 deviates from 800 by 50% > 20% threshold → dropped
        val nn = listOf(800.0, 800.0, 1200.0, 800.0, 800.0)
        val cleaned = HrvAnalyzer.rejectEctopic(nn)
        assertFalse("ectopic beat should be dropped", cleaned.contains(1200.0))
        assertEquals(4, cleaned.size)
    }

    @Test fun rejectEctopic_keepsNormalVariability() {
        val nn = listOf(800.0, 810.0, 820.0, 815.0, 805.0)
        val cleaned = HrvAnalyzer.rejectEctopic(nn)
        // All within 20% of their neighbours → none dropped
        assertEquals(5, cleaned.size)
    }

    @Test fun rejectEctopic_shortListUnchanged() {
        val nn = listOf(800.0, 850.0)
        val cleaned = HrvAnalyzer.rejectEctopic(nn)
        assertEquals(2, cleaned.size)
    }

    // ── cleanRR ────────────────────────────────────────────────────────────

    @Test fun cleanRR_appliesBothFilters() {
        val rr = listOf(200.0, 800.0, 1200.0, 800.0, 2500.0, 800.0)
        val clean = HrvAnalyzer.cleanRR(rr)
        // 200 and 2500 are out of range → dropped
        // 1200 is ectopic → dropped
        assertFalse(clean.contains(200.0))
        assertFalse(clean.contains(2500.0))
    }

    // ── analyzeRaw ─────────────────────────────────────────────────────────

    @Test fun analyzeRaw_tooFewBeats() {
        val raw = (1..10).map { 800.0 }
        val result = HrvAnalyzer.analyzeRaw(raw)
        assertNull(result.rmssd)
        assertEquals(10, result.nInput)
        assertEquals(0, result.nClean)
    }

    @Test fun analyzeRaw_cleanSeries() {
        // 25 clean intervals with some variability
        val raw = (0 until 25).map { 800.0 + (it % 5) * 10.0 }
        val result = HrvAnalyzer.analyzeRaw(raw)
        assertNotNull(result.rmssd)
        assertNotNull(result.sdnn)
        assertNotNull(result.meanNN)
        assertNotNull(result.pnn50)
        assertEquals(25, result.nInput)
        assertEquals(25, result.nClean)
        // Mean should be around 820 (average of 800,810,820,830,840)
        assertEquals(820.0, result.meanNN!!, 5.0)
    }

    @Test fun analyzeRaw_withEctopics() {
        // 30 intervals, 5 are ectopic
        val raw = (0 until 30).map {
            if (it % 6 == 0) 1500.0 else 800.0 + (it % 5) * 10.0
        }
        val result = HrvAnalyzer.analyzeRaw(raw)
        // Some beats should be cleaned out
        assertTrue(result.nClean < result.nInput)
        assertTrue(result.nClean >= HrvAnalyzer.MIN_BEATS)
        assertNotNull(result.rmssd)
    }

    @Test fun analyzeRaw_maxRejectedFraction() {
        // 30 intervals, 15 are out of range (>50% rejected)
        val raw = (0 until 30).map {
            if (it % 2 == 0) 200.0 else 800.0
        }
        val result = HrvAnalyzer.analyzeRaw(raw, maxRejectedFraction = 0.35)
        // More than 35% rejected → empty result
        assertNull(result.rmssd)
    }

    // ── analyze (with RrInterval) ──────────────────────────────────────────

    @Test fun analyze_windowFilter() {
        val rr = (0 until 25).map {
            RrInterval(deviceId = "d", ts = it.toLong() * 10, rrMs = 800 + it % 5 * 10)
        }
        // Window: ts 50..200 → only 16 samples
        val result = HrvAnalyzer.analyze(rr, windowStart = 50L, windowEnd = 200L)
        assertTrue(result.nInput <= 16)
    }

    // ── median ─────────────────────────────────────────────────────────────

    @Test fun median_oddLength() {
        assertEquals(5.0, HrvAnalyzer.median(listOf(1.0, 3.0, 5.0, 7.0, 9.0)), 0.001)
    }

    @Test fun median_evenLength() {
        assertEquals(4.0, HrvAnalyzer.median(listOf(1.0, 3.0, 5.0, 7.0)), 0.001)
    }

    @Test fun median_empty() {
        assertEquals(0.0, HrvAnalyzer.median(emptyList()), 0.001)
    }

    @Test fun median_unsorted() {
        assertEquals(5.0, HrvAnalyzer.median(listOf(9.0, 1.0, 5.0, 3.0, 7.0)), 0.001)
    }

    // ── rollingRmssd ───────────────────────────────────────────────────────

    @Test fun rollingRmssd_emptyInput() {
        assertTrue(HrvAnalyzer.rollingRmssd(emptyList()).isEmpty())
    }

    @Test fun rollingRmssd_tooFewBeats() {
        val rr = (0 until 5).map {
            RrInterval(deviceId = "d", ts = it.toLong(), rrMs = 800)
        }
        assertTrue(HrvAnalyzer.rollingRmssd(rr, minBeatsPerWindow = 8).isEmpty())
    }

    @Test fun rollingRmssd_producesEstimates() {
        val rr = (0 until 100).map {
            RrInterval(deviceId = "d", ts = it.toLong(), rrMs = 800 + (it % 5) * 10)
        }
        val rolling = HrvAnalyzer.rollingRmssd(rr, windowSec = 50, minBeatsPerWindow = 8)
        assertTrue(rolling.isNotEmpty())
        for ((_, rmssd) in rolling) {
            assertTrue(rmssd >= 0.0)
        }
    }

    // ── Constants ──────────────────────────────────────────────────────────

    @Test fun constants_sane() {
        assertEquals(300.0, HrvAnalyzer.RR_MIN_MS, 0.001)
        assertEquals(2000.0, HrvAnalyzer.RR_MAX_MS, 0.001)
        assertEquals(20, HrvAnalyzer.MIN_BEATS)
        assertEquals(0.20, HrvAnalyzer.ECTOPIC_THRESHOLD, 0.001)
        assertEquals(2, HrvAnalyzer.ECTOPIC_WINDOW_RADIUS)
    }
}
