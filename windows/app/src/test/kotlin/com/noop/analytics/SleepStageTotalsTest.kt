package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SleepStageTotals] — stage-minute decoding from both JSON
 * shapes (segment array + dict), daily aggregate, inter-fragment awake gaps,
 * and main-night selection / bridging.
 */
class SleepStageTotalsTest {

    // ── minutes: segment array format [{start,end,stage}] ─────────────────

    @Test fun minutes_nullReturnsNull() {
        assertNull(SleepStageTotals.minutes(null))
    }

    @Test fun minutes_emptyArrayReturnsNull() {
        assertNull(SleepStageTotals.minutes("[]"))
    }

    @Test fun minutes_segmentArray_wakeLightDeepRem() {
        // 30-min segments: wake 0–1800s, light 1800–3600s, deep 3600–5400s, rem 5400–7200s
        val json = """[
            {"start":0,"end":1800,"stage":"wake"},
            {"start":1800,"end":3600,"stage":"light"},
            {"start":3600,"end":5400,"stage":"deep"},
            {"start":5400,"end":7200,"stage":"rem"}
        ]"""
        val m = SleepStageTotals.minutes(json)!!
        assertEquals(30.0, m.awake, 0.001)
        assertEquals(30.0, m.light, 0.001)
        assertEquals(30.0, m.deep, 0.001)
        assertEquals(30.0, m.rem, 0.001)
        // asleep = light + deep + rem = 90 min
        assertEquals(90.0, m.asleep, 0.001)
        // inBed = asleep + awake = 120 min
        assertEquals(120.0, m.inBed, 0.001)
    }

    @Test fun minutes_segmentArray_awakeAliasForWake() {
        // "awake" and "wake" both map to the awake bucket
        val json = """[
            {"start":0,"end":600,"stage":"awake"},
            {"start":600,"end":3600,"stage":"light"}
        ]"""
        val m = SleepStageTotals.minutes(json)!!
        assertEquals(10.0, m.awake, 0.001)
        assertEquals(50.0, m.light, 0.001)
    }

    @Test fun minutes_segmentArray_zeroDurationSkipped() {
        val json = """[
            {"start":100,"end":100,"stage":"wake"},
            {"start":100,"end":4000,"stage":"light"}
        ]"""
        val m = SleepStageTotals.minutes(json)!!
        // The zero-duration wake segment is skipped; only light 3900s = 65 min
        assertEquals(0.0, m.awake, 0.001)
        assertEquals(65.0, m.light, 0.001)
    }

    // ── minutes: dict format {awake,light,deep,rem} ───────────────────────

    @Test fun minutes_dictFormat() {
        val json = """{"awake":15.0,"light":120.0,"deep":60.0,"rem":45.0}"""
        val m = SleepStageTotals.minutes(json)!!
        assertEquals(15.0, m.awake, 0.001)
        assertEquals(120.0, m.light, 0.001)
        assertEquals(60.0, m.deep, 0.001)
        assertEquals(45.0, m.rem, 0.001)
        assertEquals(225.0, m.asleep, 0.001)  // 120+60+45
        assertEquals(240.0, m.inBed, 0.001)    // 225+15
    }

    @Test fun minutes_dictFormat_zeroInBedReturnsNull() {
        val json = """{"awake":0.0,"light":0.0,"deep":0.0,"rem":0.0}"""
        assertNull(SleepStageTotals.minutes(json))
    }

    @Test fun minutes_invalidJsonReturnsNull() {
        assertNull(SleepStageTotals.minutes("not json at all"))
    }

    // ── dailyAggregate ────────────────────────────────────────────────────

    @Test fun dailyAggregate_singleSession() {
        val stages = """[
            {"start":0,"end":1800,"stage":"wake"},
            {"start":1800,"end":7200,"stage":"light"}
        ]"""
        val agg = SleepStageTotals.dailyAggregate(listOf(stages))!!
        // awake = 30 min, light = 90 min, asleep = 90, inBed = 120
        assertEquals(90.0, agg.totalSleepMin, 0.001)
        assertEquals(0.75, agg.efficiency, 0.001)  // 90/120
        assertEquals(90.0, agg.lightMin, 0.001)
        assertEquals(0.0, agg.deepMin, 0.001)
        assertEquals(0.0, agg.remMin, 0.001)
    }

    @Test fun dailyAggregate_multipleSessions() {
        val s1 = """[{"start":0,"end":3600,"stage":"deep"}]"""   // 60 min deep
        val s2 = """[{"start":0,"end":3600,"stage":"rem"}]"""    // 60 min rem
        val agg = SleepStageTotals.dailyAggregate(listOf(s1, s2))!!
        assertEquals(120.0, agg.totalSleepMin, 0.001)
        assertEquals(60.0, agg.deepMin, 0.001)
        assertEquals(60.0, agg.remMin, 0.001)
    }

    @Test fun dailyAggregate_emptyListReturnsNull() {
        assertNull(SleepStageTotals.dailyAggregate(emptyList()))
    }

    @Test fun dailyAggregate_allNullReturnsNull() {
        assertNull(SleepStageTotals.dailyAggregate(listOf(null, null)))
    }

    @Test fun dailyAggregate_withInterFragmentAwake() {
        val s1 = """[{"start":0,"end":3600,"stage":"light"}]"""  // 60 min light
        val s2 = """[{"start":0,"end":3600,"stage":"light"}]"""  // 60 min light
        // 20 min inter-fragment awake (1200 s)
        val agg = SleepStageTotals.dailyAggregate(listOf(s1, s2), interFragmentAwakeSeconds = 1200.0)!!
        // asleep = 120 min, awake = 20 min, inBed = 140
        assertEquals(120.0, agg.totalSleepMin, 0.001)
        assertEquals(120.0 / 140.0, agg.efficiency, 0.001)
    }

    // ── interFragmentAwakeSeconds ─────────────────────────────────────────

    @Test fun interFragmentAwakeSeconds_singleSpan() {
        assertEquals(0.0, SleepStageTotals.interFragmentAwakeSeconds(listOf(0L to 3600L)), 0.001)
    }

    @Test fun interFragmentAwakeSeconds_twoSpansWithGap() {
        // Fragment 1: [0, 3600), Fragment 2: [5400, 7200) → gap = 1800 s
        val gap = SleepStageTotals.interFragmentAwakeSeconds(listOf(0L to 3600L, 5400L to 7200L))
        assertEquals(1800.0, gap, 0.001)
    }

    @Test fun interFragmentAwakeSeconds_overlappingSpansNoGap() {
        // Fragment 1: [0, 3600), Fragment 2: [3000, 7200) → overlap, no gap
        val gap = SleepStageTotals.interFragmentAwakeSeconds(listOf(0L to 3600L, 3000L to 7200L))
        assertEquals(0.0, gap, 0.001)
    }

    // ── bridgeAdjacent ────────────────────────────────────────────────────

    @Test fun bridgeAdjacent_singleBlockUnchanged() {
        val blocks = listOf(SleepStageTotals.NightBlock(0L, 3600L))
        assertEquals(1, SleepStageTotals.bridgeAdjacent(blocks).size)
    }

    @Test fun bridgeAdjacent_mergesShortGap() {
        // Two blocks 30 min apart (< 60 min GAP_BRIDGE_MAX_MIN) → merged
        val blocks = listOf(
            SleepStageTotals.NightBlock(0L, 3600L),       // 0–1h
            SleepStageTotals.NightBlock(5400L, 9000L),     // 1.5–2.5h (gap = 1800s = 30min)
        )
        val bridged = SleepStageTotals.bridgeAdjacent(blocks)
        assertEquals(1, bridged.size)
        assertEquals(0L, bridged[0].start)
        assertEquals(9000L, bridged[0].end)
    }

    @Test fun bridgeAdjacent_keepsLongGap() {
        // Two blocks 90 min apart (> 60 min GAP_BRIDGE_MAX_MIN) → not merged
        val blocks = listOf(
            SleepStageTotals.NightBlock(0L, 3600L),        // 0–1h
            SleepStageTotals.NightBlock(9000L, 12600L),     // 2.5–3.5h (gap = 5400s = 90min)
        )
        val bridged = SleepStageTotals.bridgeAdjacent(blocks)
        assertEquals(2, bridged.size)
    }

    // ── mainNightIndex ────────────────────────────────────────────────────

    @Test fun mainNightIndex_emptyReturnsNull() {
        assertNull(SleepStageTotals.mainNightIndex(emptyList(), 0L))
    }

    @Test fun mainNightIndex_singleBlock() {
        val blocks = listOf(SleepStageTotals.NightBlock(0L, 3600L))
        assertEquals(0, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    @Test fun mainNightIndex_picksLongerBlock() {
        // Block 0: 1h duration. Block 1: 3h duration. Block 1 should win on duration.
        val blocks = listOf(
            SleepStageTotals.NightBlock(0L, 3600L),         // 1h
            SleepStageTotals.NightBlock(7200L, 18000L),      // 3h
        )
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    // ── mainNightSelection ────────────────────────────────────────────────

    @Test fun mainNightSelection_singleBlock_onlyBlock() {
        val blocks = listOf(SleepStageTotals.NightBlock(0L, 3600L))
        val sel = SleepStageTotals.mainNightSelection(blocks, 0L)!!
        assertEquals(0, sel.index)
        assertEquals(SleepStageTotals.MainNightReason.onlyBlock, sel.reason)
        assertEquals(3600L, sel.asleepSec)
    }

    @Test fun mainNightSelection_twoBlocks_longestWins() {
        val blocks = listOf(
            SleepStageTotals.NightBlock(0L, 3600L),         // 1h
            SleepStageTotals.NightBlock(7200L, 18000L),      // 3h
        )
        val sel = SleepStageTotals.mainNightSelection(blocks, 0L)!!
        assertEquals(1, sel.index)
        // Cold-start (no habitual midsleep) → "longest"
        assertEquals(SleepStageTotals.MainNightReason.longest, sel.reason)
    }

    // ── isOvernightOnset ──────────────────────────────────────────────────

    @Test fun isOvernightOnset_lateEvening() {
        // 22:00 UTC, offset 0 → hour 22 ≥ 20 → overnight
        assertTrue(SleepStageTotals.isOvernightOnset(22 * 3600L, 0L))
    }

    @Test fun isOvernightOnset_earlyMorning() {
        // 03:00 UTC, offset 0 → hour 3 < 11 → overnight
        assertTrue(SleepStageTotals.isOvernightOnset(3 * 3600L, 0L))
    }

    @Test fun isOvernightOnset_midday() {
        // 12:00 UTC, offset 0 → hour 12, not in [20, 11) → not overnight
        assertFalse(SleepStageTotals.isOvernightOnset(12 * 3600L, 0L))
    }

    @Test fun isOvernightOnset_afternoon() {
        // 15:00 UTC, offset 0 → hour 15, not overnight
        assertFalse(SleepStageTotals.isOvernightOnset(15 * 3600L, 0L))
    }

    // ── dailyAggregateHonoringEdits ───────────────────────────────────────

    @Test fun dailyAggregateHonoringEdits_noEdits() {
        val detected = listOf(0L to """[{"start":0,"end":3600,"stage":"light"}]""")
        val agg = SleepStageTotals.dailyAggregateHonoringEdits(detected, emptyMap())!!
        assertFalse(agg.editApplied)
        assertEquals(60.0, agg.sleep.totalSleepMin, 0.001)
    }

    @Test fun dailyAggregateHonoringEdits_withEdit() {
        val detected = listOf(0L to """[{"start":0,"end":3600,"stage":"light"}]""")
        val edited = mapOf(0L to """[{"start":0,"end":3600,"stage":"deep"}]""")
        val agg = SleepStageTotals.dailyAggregateHonoringEdits(detected, edited)!!
        assertTrue(agg.editApplied)
        assertEquals(60.0, agg.sleep.deepMin, 0.001)
        assertEquals(0.0, agg.sleep.lightMin, 0.001)
    }

    @Test fun dailyAggregateHonoringEdits_manualBlockAdded() {
        val detected = listOf(0L to """[{"start":0,"end":3600,"stage":"light"}]""")
        val manual = listOf(100L to """[{"start":0,"end":3600,"stage":"rem"}]""")
        // No onset map → legacy sum-of-all-blocks
        val agg = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = detected, edited = emptyMap(), manual = manual,
        )!!
        assertTrue(agg.editApplied)
        assertEquals(120.0, agg.sleep.totalSleepMin, 0.001)
    }

    @Test fun dailyAggregateHonoringEdits_nullEditFallsBackToDetected() {
        val detected = listOf(0L to """[{"start":0,"end":3600,"stage":"light"}]""")
        // Edit maps to null → NOT applied, falls back to detected
        val edited = mapOf<Long, String?>(0L to null)
        val agg = SleepStageTotals.dailyAggregateHonoringEdits(detected, edited)!!
        assertFalse(agg.editApplied)
        assertEquals(60.0, agg.sleep.lightMin, 0.001)
    }
}
