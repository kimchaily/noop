package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skin-temperature baseline must be folded from the RECORD, not from the nights one pass happened
 * to scan.
 *
 * The bug this pins: every other Charge driver is a column on the daily row, so the fold reads the whole
 * stored history back. Skin temp had no column — the row carries the DEVIATION, and a deviation cannot be
 * re-folded into the baseline it was measured against — so its baseline was rebuilt from the in-memory
 * means of the days the current pass scored. A routine incremental pass scores ONE night. One night is
 * below [Baselines.minNightsSeed], so the baseline was never usable, the term was gated out of Charge, and
 * `skinTempDevC` was written null — which also left the illness early-warning without the signal that sits
 * nearest a fever. Only a full-history rebuild ever produced a skin-temp reading, and the next incremental
 * pass wiped it again.
 *
 * The fix persists the nightly mean as its own metric series, so membership becomes the same rule the other
 * three drivers use. These tests mirror that construction and assert the properties it has to have.
 * [ScoringFingerprint] cannot catch this class of change — the fixture supplies its own nights — which is
 * what [ScoringFingerprint.INPUT_REVISION] exists for.
 */
class SkinTempBaselineMembershipTest {

    private val cfg = Baselines.metricCfg.getValue("skin_temp")

    /** Three settled weeks of nightly means, then the night under test. */
    private val dayRows: List<String> = (1..22).map { "2026-08-%02d".format(it) }
    private val storedMeans: Map<String, Double> =
        dayRows.dropLast(1).mapIndexed { i, d -> d to 33.4 + (i % 3) * 0.1 }.toMap()
    private val dayUnderTest = dayRows.last()

    /**
     * The EXACT map construction from `analyzeRecentOnCpu`: every day row is a member, a day without a
     * persisted mean is a member holding null, and the keys are sorted so values and keys stay parallel.
     */
    private fun membership(rows: List<String>, stored: Map<String, Double>): Pair<List<String>, List<Double?>> {
        val m = LinkedHashMap<String, Double?>()
        for (day in rows) m[day] = null
        for ((day, mean) in stored) m[day] = mean
        val sorted = m.entries.sortedBy { it.key }
        return sorted.map { it.key } to sorted.map { it.value }
    }

    private fun priorFor(rows: List<String>, stored: Map<String, Double>, day: String): BaselineState {
        val (keys, values) = membership(rows, stored)
        return IntelligenceEngine.PriorBaselines(keys, values, cfg).before(day)
    }

    @Test
    fun theOldWindowScopedMembershipCouldNeverSeedABaseline() {
        // What an incremental pass used to fold: the one night it scored, and nothing else.
        val onlyThisPass = IntelligenceEngine.PriorBaselines(
            listOf(dayUnderTest), listOf(33.5), cfg,
        ).before(dayUnderTest)
        assertFalse(
            "one scanned night cannot seed a ${Baselines.minNightsSeed}-night baseline, so the term was dropped",
            onlyThisPass.usable,
        )
        assertEquals(0, onlyThisPass.nValid)
    }

    @Test
    fun theRecordScopedMembershipSeedsIt() {
        val prior = priorFor(dayRows, storedMeans, dayUnderTest)
        assertTrue("21 stored nights must produce a usable baseline", prior.usable)
        assertEquals(storedMeans.size, prior.nValid)
    }

    /** The load-bearing property: the same night resolves the same baseline however wide the pass was. */
    @Test
    fun anIncrementalPassNoLongerSeesADifferentBaselineThanAFullRebuild() {
        // Membership as it used to be: whatever nights the pass itself scanned.
        fun windowScoped(scanned: List<String>) = IntelligenceEngine.PriorBaselines(
            scanned, scanned.map { storedMeans[it] }, cfg,
        ).before(dayUnderTest)

        // A full rebuild scanned every night; a routine pass scanned one. Same day, two baselines — and
        // only the rebuild's was ever usable, so the term appeared once and vanished at the next pass.
        assertNotEquals(windowScoped(dayRows), windowScoped(listOf(dayUnderTest)))
        assertTrue(windowScoped(dayRows).usable)
        assertFalse(windowScoped(listOf(dayUnderTest)).usable)

        // Now both read the same stored series back, so the routine pass resolves the rebuild's baseline.
        assertEquals(windowScoped(dayRows), priorFor(dayRows, storedMeans, dayUnderTest))
        // Which is exactly the fold over the nights strictly BEFORE the day — never one that includes it.
        assertEquals(
            Baselines.foldHistory(dayRows.dropLast(1).map { storedMeans[it] }, cfg),
            priorFor(dayRows, storedMeans, dayUnderTest),
        )
    }

    /** A night that produced no mean is a member holding null, not an absence. */
    @Test
    fun aGapAgesTheBaselineOutInsteadOfBeingStitchedClosed() {
        val strapOff = (Baselines.staleDays + 2)
        val rows = dayRows + (1..strapOff).map { "2026-09-%02d".format(it) }
        val later = "2026-09-%02d".format(strapOff)
        val prior = priorFor(rows, storedMeans, later)
        assertEquals(BaselineStatus.STALE, prior.status)
        assertFalse("a baseline weeks out of date must not be scored against", prior.usable)

        // Trimming membership to the days that carry a value is the tempting shortcut, and it is wrong:
        // it makes the same night look freshly calibrated.
        val trimmed = IntelligenceEngine.PriorBaselines(
            storedMeans.keys.sorted(), storedMeans.keys.sorted().map { storedMeans[it] }, cfg,
        ).before(later)
        assertTrue(trimmed.usable)
    }

    /** With a usable baseline the deviation exists, and a deviation moves Charge. Both halves matter. */
    @Test
    fun aUsableBaselineIsWhatPutsTheTermIntoCharge() {
        val prior = priorFor(dayRows, storedMeans, dayUnderTest)
        val dev = Baselines.deviation(34.3, prior).delta

        val hrvBase = Baselines.foldHistory(List(21) { 55.0 }, Baselines.hrvCfg)
        fun charge(skinTempDev: Double?): Double? = RecoveryScorer.recovery(
            hrv = 52.0,
            rhr = 44.0,
            resp = null,
            hrvBaseline = RecoveryScorer.DriverBaseline(hrvBase),
            rhrBaseline = null,
            respBaseline = null,
            sleepPerf = 0.77,
            skinTempDev = skinTempDev,
            hrvBaselineUsable = hrvBase.usable,
        )
        assertTrue(
            "the skin-temp term must actually reach the score, or persisting the mean buys nothing",
            charge(dev) != charge(null),
        )
    }
}
