package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [HrZones] — Tanaka HRmax, 5-zone construction, zone-number
 * lookup, and time-in-zone accumulation from an HR sample stream.
 */
class HrZonesTest {

    // ── Tanaka HRmax ──────────────────────────────────────────────────────

    @Test fun tanakaMaxHR_age30() {
        // 208 − 0.7 × 30 = 208 − 21 = 187.0
        assertEquals(187.0, HrZones.tanakaMaxHR(30.0), 0.001)
    }

    @Test fun tanakaMaxHR_age40() {
        // 208 − 0.7 × 40 = 208 − 28 = 180.0
        assertEquals(180.0, HrZones.tanakaMaxHR(40.0), 0.001)
    }

    @Test fun tanakaMaxHR_age0() {
        // 208 − 0 = 208.0
        assertEquals(208.0, HrZones.tanakaMaxHR(0.0), 0.001)
    }

    // ── Zone construction ─────────────────────────────────────────────────

    @Test fun zones_fromAge_usesTanaka() {
        val zs = HrZones.zones(age = 30.0)
        assertEquals("tanaka", zs.source)
        assertEquals(187.0, zs.maxHR, 0.001)
        assertEquals(5, zs.zones.size)
    }

    @Test fun zones_fromManualOverride_usesManual() {
        val zs = HrZones.zones(age = 30.0, maxHROverride = 200.0)
        assertEquals("manual", zs.source)
        assertEquals(200.0, zs.maxHR, 0.001)
    }

    @Test fun zones_fiveZonesInAscendingOrder() {
        val zs = HrZones.zones(maxHR = 200.0)
        for (i in 0 until 5) {
            assertEquals(i + 1, zs.zones[i].number)
        }
        // Each zone's lower == previous zone's upper
        for (i in 1 until 5) {
            assertEquals(zs.zones[i - 1].upper, zs.zones[i].lower, 0.001)
        }
    }

    @Test fun zones_zoneBoundaries_200maxHR() {
        // maxHR = 200, edges [0.50, 0.60, 0.70, 0.80, 0.90, 1.00]
        val zs = HrZones.zones(maxHR = 200.0)
        // Zone 1: 100–120
        assertEquals(100.0, zs.zones[0].lower, 0.001)
        assertEquals(120.0, zs.zones[0].upper, 0.001)
        // Zone 3: 140–160
        assertEquals(140.0, zs.zones[2].lower, 0.001)
        assertEquals(160.0, zs.zones[2].upper, 0.001)
        // Zone 5: 180–200
        assertEquals(180.0, zs.zones[4].lower, 0.001)
        assertEquals(200.0, zs.zones[4].upper, 0.001)
    }

    // ── zoneNumber lookup ─────────────────────────────────────────────────

    @Test fun zoneNumber_belowZone1_returns0() {
        val zs = HrZones.zones(maxHR = 200.0)
        assertEquals(0, zs.zoneNumber(50.0))   // 50 < 100 (50% of 200)
    }

    @Test fun zoneNumber_zone1LowerEdge() {
        val zs = HrZones.zones(maxHR = 200.0)
        assertEquals(1, zs.zoneNumber(100.0))  // exactly 50% of 200
    }

    @Test fun zoneNumber_zone2() {
        val zs = HrZones.zones(maxHR = 200.0)
        assertEquals(2, zs.zoneNumber(130.0))  // 130 in [120, 140)
    }

    @Test fun zoneNumber_zone5_includesUpperEdge() {
        val zs = HrZones.zones(maxHR = 200.0)
        // Top zone is inclusive at upper: HRmax itself → zone 5
        assertEquals(5, zs.zoneNumber(200.0))
        assertEquals(5, zs.zoneNumber(195.0))
    }

    @Test fun zoneNumber_zone4UpperEdge_goesTo5() {
        val zs = HrZones.zones(maxHR = 200.0)
        // 180 is the lower bound of zone 5 (upper of zone 4 is exclusive)
        assertEquals(5, zs.zoneNumber(180.0))
    }

    // ── timeInZone ────────────────────────────────────────────────────────

    @Test fun timeInZone_emptyStream() {
        val zs = HrZones.zones(maxHR = 200.0)
        val tiz = HrZones.timeInZone(emptyList(), zs)
        assertEquals(0.0, tiz.total, 0.001)
        assertEquals(0.0, tiz.belowZone1, 0.001)
    }

    @Test fun timeInZone_singleSample_getsMedianInterval() {
        // Single sample → medianInterval = 1.0 s
        val zs = HrZones.zones(maxHR = 200.0)
        val hr = listOf(HrSample("d", 0L, 150))
        val tiz = HrZones.timeInZone(hr, zs)
        // 150 bpm → zone 3 (140–160)
        assertEquals(1.0, tiz.secondsInZone(3), 0.001)
    }

    @Test fun timeInZone_twoSamplesInSameZone() {
        val zs = HrZones.zones(maxHR = 200.0)
        // Two samples 10 s apart, both at 150 bpm (zone 3)
        val hr = listOf(
            HrSample("d", 0L, 150),
            HrSample("d", 10L, 150),
        )
        val tiz = HrZones.timeInZone(hr, zs)
        // First sample: 10 s (gap to next). Last sample: 10 s (median interval).
        assertEquals(20.0, tiz.secondsInZone(3), 0.001)
    }

    @Test fun timeInZone_samplesInDifferentZones() {
        val zs = HrZones.zones(maxHR = 200.0)
        // Sample 1: 110 bpm (zone 1), 10s gap. Sample 2: 190 bpm (zone 5), tail.
        val hr = listOf(
            HrSample("d", 0L, 110),
            HrSample("d", 10L, 190),
        )
        val tiz = HrZones.timeInZone(hr, zs)
        assertEquals(10.0, tiz.secondsInZone(1), 0.001)  // first sample credited 10s
        assertEquals(10.0, tiz.secondsInZone(5), 0.001)  // tail sample gets median = 10s
    }

    @Test fun timeInZone_belowZone1() {
        val zs = HrZones.zones(maxHR = 200.0)
        // 80 bpm < 100 (zone 1 lower) → below zone 1
        val hr = listOf(
            HrSample("d", 0L, 80),
            HrSample("d", 10L, 80),
        )
        val tiz = HrZones.timeInZone(hr, zs)
        assertEquals(0.0, tiz.secondsInZone(1), 0.001)
        assertTrue(tiz.belowZone1 > 0.0)
    }

    // ── medianInterval ────────────────────────────────────────────────────

    @Test fun medianInterval_singleElement_returns1() {
        val hr = listOf(HrSample("d", 0L, 60))
        assertEquals(1.0, HrZones.medianInterval(hr), 0.001)
    }

    @Test fun medianInterval_uniformGap() {
        // 5 samples, 5 s apart → median gap = 5.0
        val hr = (0 until 5).map { HrSample("d", it.toLong() * 5, 60) }
        assertEquals(5.0, HrZones.medianInterval(hr), 0.001)
    }

    @Test fun medianInterval_capsAt300s() {
        // Gaps > 300 s are excluded from the median
        val hr = listOf(
            HrSample("d", 0L, 60),
            HrSample("d", 10L, 60),
            HrSample("d", 500L, 60),  // 490 s gap — excluded
        )
        // Only gap in (0, 300) is 10 → median = 10.0
        assertEquals(10.0, HrZones.medianInterval(hr), 0.001)
    }
}
