package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [Baselines] — EWMA baseline seeding, skip-and-hold, outlier
 * rejection, deviation z-score, rolling mean/SD, and status computation.
 */
class BaselinesTest {

    // ── Constants & config ────────────────────────────────────────────────

    @Test fun metricCfg_hrvConfigCorrect() {
        val cfg = Baselines.hrvCfg
        assertEquals(5.0, cfg.minVal, 0.001)
        assertEquals(250.0, cfg.maxVal, 0.001)
        assertEquals(5.0, cfg.floorSpread, 0.001)
        assertEquals(14.0, cfg.halfLifeB, 0.001)
        assertEquals(21.0, cfg.halfLifeS, 0.001)
    }

    @Test fun metricCfg_allFourMetricsPresent() {
        assertTrue(Baselines.metricCfg.containsKey("hrv"))
        assertTrue(Baselines.metricCfg.containsKey("resting_hr"))
        assertTrue(Baselines.metricCfg.containsKey("resp"))
        assertTrue(Baselines.metricCfg.containsKey("skin_temp"))
    }

    // ── computeStatus ─────────────────────────────────────────────────────

    @Test fun computeStatus_coldStart() {
        assertEquals(BaselineStatus.CALIBRATING, Baselines.computeStatus(3, 0))
    }

    @Test fun computeStatus_provisional() {
        assertEquals(BaselineStatus.PROVISIONAL, Baselines.computeStatus(5, 0))
    }

    @Test fun computeStatus_trusted() {
        assertEquals(BaselineStatus.TRUSTED, Baselines.computeStatus(14, 0))
    }

    @Test fun computeStatus_stale() {
        assertEquals(BaselineStatus.STALE, Baselines.computeStatus(14, 15))
    }

    // ── update: seeding ───────────────────────────────────────────────────

    @Test fun update_firstNightSeeds() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.update(null, 50.0, cfg)
        assertNotNull(state)
        assertEquals(50.0, state!!.baseline, 0.001)
        assertEquals(cfg.floorSpread, state.spread, 0.001)
        assertEquals(1, state.nValid)
        assertEquals(BaselineStatus.CALIBRATING, state.status)
    }

    @Test fun update_firstNightOutOfRangeSeedsMidpoint() {
        val cfg = Baselines.hrvCfg
        // 300 > maxVal 250 → seed at midpoint (5+250)/2 = 127.5
        val state = Baselines.update(null, 300.0, cfg)
        assertEquals(127.5, state.baseline, 0.001)
        assertEquals(0, state.nValid)
    }

    @Test fun update_firstNightNullSeedsMidpoint() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.update(null, null, cfg)
        assertEquals(127.5, state.baseline, 0.001)
        assertEquals(0, state.nValid)
        assertEquals(1, state.nightsSinceUpdate)
    }

    // ── update: skip-and-hold ─────────────────────────────────────────────

    @Test fun update_missingNightCarriesForward() {
        val cfg = Baselines.hrvCfg
        val seeded = Baselines.update(null, 50.0, cfg)
        val held = Baselines.update(seeded, null, cfg)
        assertEquals(seeded.baseline, held.baseline, 0.001)
        assertEquals(seeded.spread, held.spread, 0.001)
        assertEquals(seeded.nValid, held.nValid)
        assertEquals(1, held.nightsSinceUpdate)
    }

    @Test fun update_outOfRangeCarriesForward() {
        val cfg = Baselines.hrvCfg
        val seeded = Baselines.update(null, 50.0, cfg)
        // 1 > minVal 5 is false, actually 1 < 5 → out of range → skip
        val held = Baselines.update(seeded, 1.0, cfg)
        assertEquals(seeded.baseline, held.baseline, 0.001)
        assertEquals(seeded.nValid, held.nValid)
    }

    // ── update: EWMA progression ──────────────────────────────────────────

    @Test fun update_ewmaMovesTowardValue() {
        val cfg = Baselines.hrvCfg
        val seeded = Baselines.update(null, 50.0, cfg)
        val updated = Baselines.update(seeded, 60.0, cfg)
        // The baseline should move toward 60 but not all the way
        assertTrue("baseline should move toward 60", updated.baseline > 50.0)
        assertTrue("baseline should not reach 60 in one step", updated.baseline < 60.0)
        assertEquals(2, updated.nValid)
    }

    @Test fun update_nValidIncrementsWithEachValidNight() {
        val cfg = Baselines.hrvCfg
        var state: BaselineState? = null
        for (v in listOf(50.0, 52.0, 48.0, 51.0)) {
            state = Baselines.update(state, v, cfg)
        }
        assertEquals(4, state!!.nValid)
        assertEquals(BaselineStatus.PROVISIONAL, state.status)
    }

    // ── foldHistory ───────────────────────────────────────────────────────

    @Test fun foldHistory_emptyListSeedsMidpoint() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.foldHistory(emptyList(), cfg)
        assertEquals(127.5, state.baseline, 0.001)
        assertEquals(0, state.nValid)
    }

    @Test fun foldHistory_multipleNights() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.foldHistory(listOf(50.0, 52.0, 48.0, 51.0), cfg)
        assertEquals(4, state.nValid)
        // Baseline should be somewhere in the 48–52 range
        assertTrue(state.baseline in 48.0..52.0)
    }

    @Test fun foldHistory_withNulls() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.foldHistory(listOf(50.0, null, 52.0, null, 48.0), cfg)
        assertEquals(3, state.nValid)
    }

    // ── deviation ─────────────────────────────────────────────────────────

    @Test fun deviation_atBaseline_isZero() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.foldHistory(listOf(50.0, 50.0, 50.0, 50.0), cfg)
        val dev = Baselines.deviation(50.0, state)
        assertEquals(0.0, dev.z, 0.01)
        assertEquals(0.0, dev.delta, 0.001)
        assertTrue(dev.inNormalRange)
    }

    @Test fun deviation_aboveBaseline_positiveZ() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.foldHistory(listOf(50.0, 50.0, 50.0, 50.0), cfg)
        val dev = Baselines.deviation(60.0, state)
        assertTrue(dev.z > 0.0)
        assertTrue(dev.delta > 0.0)
    }

    @Test fun deviation_belowBaseline_negativeZ() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.foldHistory(listOf(50.0, 50.0, 50.0, 50.0), cfg)
        val dev = Baselines.deviation(40.0, state)
        assertTrue(dev.z < 0.0)
        assertTrue(dev.delta < 0.0)
    }

    @Test fun deviation_inNormalRange() {
        val cfg = Baselines.hrvCfg
        // Build a state with a spread that makes |z| ≤ 1.0 for a small deviation
        val state = BaselineState(
            baseline = 50.0, spread = 10.0, nValid = 14,
            nightsSinceUpdate = 0, status = BaselineStatus.TRUSTED,
        )
        // z = (55 − 50) / (1.253 × 10) = 5 / 12.53 ≈ 0.399 → in range
        val dev = Baselines.deviation(55.0, state)
        assertTrue(dev.inNormalRange)
    }

    // ── rollingMeanSD ─────────────────────────────────────────────────────

    @Test fun rollingMeanSD_emptyList() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.rollingMeanSD(emptyList(), cfg)
        assertEquals(0, state.nValid)
        assertEquals(BaselineStatus.CALIBRATING, state.status)
    }

    @Test fun rollingMeanSD_singleValue() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.rollingMeanSD(listOf(50.0), cfg)
        assertEquals(1, state.nValid)
        assertEquals(50.0, state.baseline, 0.001)
    }

    @Test fun rollingMeanSD_multipleValues() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.rollingMeanSD(listOf(50.0, 60.0, 55.0), cfg)
        assertEquals(3, state.nValid)
        // Mean = (50 + 60 + 55) / 3 = 55.0
        assertEquals(55.0, state.baseline, 0.001)
    }

    @Test fun rollingMeanSD_filtersNullsAndOutOfRange() {
        val cfg = Baselines.hrvCfg
        val state = Baselines.rollingMeanSD(listOf(50.0, null, 1.0, 60.0), cfg)
        // null and 1.0 (< minVal 5) are dropped: only 50.0 and 60.0 count
        assertEquals(2, state.nValid)
        assertEquals(55.0, state.baseline, 0.001)
    }

    @Test fun rollingMeanSD_windowCapsTrailingNights() {
        val cfg = Baselines.hrvCfg
        // 10 values, window = 5: only last 5 count
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        val state = Baselines.rollingMeanSD(values, cfg, window = 5)
        assertEquals(5, state.nValid)
        // Mean of last 5 = (60+70+80+90+100)/5 = 80.0
        assertEquals(80.0, state.baseline, 0.001)
    }

    // ── recalibrateRecoveryBaselines ──────────────────────────────────────

    @Test fun recalibrateRecoveryBaselines_writesBothEpochs() {
        val written = mutableMapOf<String, Long>()
        Baselines.recalibrateRecoveryBaselines({ k, v -> written[k] = v }, 1_700_000_000L)
        assertEquals(1_700_000_000L, written[Baselines.hrvBaselineEpochKey])
        assertEquals(1_700_000_000L, written[Baselines.recoveryBaselineEpochKey])
    }
}
