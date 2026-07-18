package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pins the shared overnight-vitals reads ([MetricReads]) that the Today vitals card, the Key-Metrics tile
 * and the Your-cards row now all funnel through. The point of this step is that the three surfaces can no
 * longer drift (#543), so alongside the plain behaviour these tests assert BYTE-FOR-BYTE equality with the
 * exact inline expressions each surface used before the extraction.
 */
class MetricReadsTest {

    private fun row(
        day: String = "2026-06-18",
        hrv: Double? = null,
        rhr: Int? = null,
        resp: Double? = null,
        spo2: Double? = null,
        steps: Int? = null,
    ) = DailyMetric(
        deviceId = "my-whoop", day = day,
        avgHrv = hrv, restingHr = rhr, respRateBpm = resp, spo2Pct = spo2, steps = steps,
    )

    // MARK: today-first / carry fallback

    @Test
    fun hrv_prefersTodaysOwnValue_overTheCarry() {
        val today = row(hrv = 62.0)
        val carry = row(hrv = 41.0)
        assertEquals("62", MetricReads.hrv(today, carry).number)
    }

    @Test
    fun hrv_fallsBackToCarry_whenTodayLacksTheField() {
        val today = row(hrv = null)
        val carry = row(hrv = 41.0)
        assertEquals("41", MetricReads.hrv(today, carry).number)
    }

    @Test
    fun hrv_noReading_whenNeitherRowHasIt() {
        val mv = MetricReads.hrv(row(hrv = null), row(hrv = null))
        assertNull(mv.number)
        assertNull(mv.frac)
        assertFalse(mv.hasValue)
        // The unit is still the canonical one; callers blank it themselves when there's no value.
        assertEquals("ms", mv.unit)
    }

    @Test
    fun hrv_noReading_whenBothRowsAreNull() {
        val mv = MetricReads.hrv(null, null)
        assertNull(mv.number)
        assertNull(mv.frac)
    }

    // MARK: rounding / formatting parity with the old inline code

    @Test
    fun hrv_roundsToWholeMs_likeTheOldInlineFormat() {
        val v = 71.6
        val mv = MetricReads.hrv(row(hrv = v), null)
        assertEquals("${v.roundToInt()}", mv.number)   // old: "${it.roundToInt()}"
        assertEquals("72", mv.number)
        assertTrue(mv.hasValue)
    }

    @Test
    fun restingHr_isThePlainInt_likeTheOldInlineFormat() {
        val v = 58
        val mv = MetricReads.restingHr(row(rhr = v), null)
        assertEquals(v.toString(), mv.number)          // old: v?.toString()
        assertEquals("bpm", mv.unit)
    }

    @Test
    fun respiratory_isOneDecimal_likeTheOldInlineFormat() {
        val v = 14.24
        val mv = MetricReads.respiratory(row(resp = v), null)
        assertEquals(String.format(Locale.US, "%.1f", v), mv.number)   // old: String.format("%.1f", it)
        assertEquals("14.2", mv.number)
        assertEquals("rpm", mv.unit)
    }

    // MARK: vessel fill (frac) parity — same ceilings, same coercion into 0..1

    @Test
    fun hrv_frac_isValueOver120_coerced() {
        assertEquals(0.5, MetricReads.hrv(row(hrv = 60.0), null).frac!!, 1e-9)
        assertEquals(1.0, MetricReads.hrv(row(hrv = 200.0), null).frac!!, 1e-9)   // coerced at the top
        assertEquals(0.0, MetricReads.hrv(row(hrv = 0.0), null).frac!!, 1e-9)
    }

    @Test
    fun restingHr_frac_isValueOver100_coerced() {
        assertEquals(0.55, MetricReads.restingHr(row(rhr = 55), null).frac!!, 1e-9)
        assertEquals(1.0, MetricReads.restingHr(row(rhr = 140), null).frac!!, 1e-9)
    }

    @Test
    fun respiratory_frac_isValueOver24_coerced() {
        assertEquals(0.5, MetricReads.respiratory(row(resp = 12.0), null).frac!!, 1e-9)
        assertEquals(1.0, MetricReads.respiratory(row(resp = 30.0), null).frac!!, 1e-9)
    }

    // MARK: the "joined" render the vitals card + Your-cards row use matches the split render the tile uses

    @Test
    fun joinedRender_matchesOldVitalsCardStrings() {
        // Old HeroMetricRows: hrv -> "${roundToInt()} ms", rhr -> "$it bpm", resp -> "%.1f rpm".
        val hrv = MetricReads.hrv(row(hrv = 55.0), null)
        val rhr = MetricReads.restingHr(row(rhr = 60), null)
        val resp = MetricReads.respiratory(row(resp = 15.0), null)
        assertEquals("55 ms", hrv.number + " " + hrv.unit)
        assertEquals("60 bpm", rhr.number + " " + rhr.unit)
        assertEquals("15.0 rpm", resp.number + " " + resp.unit)
    }

    // MARK: blood oxygen — whole percent, 100% ceiling, bare number (surfaces add the % their own way)

    @Test
    fun bloodOxygen_isWholePercent_likeTheOldInlineFormat() {
        val v = 96.7
        val mv = MetricReads.bloodOxygen(row(spo2 = v), null)
        assertEquals(String.format(Locale.US, "%.0f", v), mv.number)   // old: "%.0f"
        assertEquals("97", mv.number)
        assertEquals("%", mv.unit)
    }

    @Test
    fun bloodOxygen_frac_isValueOver100_coerced() {
        assertEquals(0.95, MetricReads.bloodOxygen(row(spo2 = 95.0), null).frac!!, 1e-9)
    }

    @Test
    fun bloodOxygen_todayFirstThenCarry_andNoReadingWhenBothNull() {
        assertEquals("98", MetricReads.bloodOxygen(row(spo2 = 98.0), row(spo2 = 90.0)).number)
        assertEquals("90", MetricReads.bloodOxygen(row(spo2 = null), row(spo2 = 90.0)).number)
        assertNull(MetricReads.bloodOxygen(null, null).number)
    }

    @Test
    fun bloodOxygen_joinedInline_matchesOldYourCardsPercentString() {
        // Old Your-cards row: "%.0f%%" -> "97%". New: number + "%".
        val mv = MetricReads.bloodOxygen(row(spo2 = 96.7), null)
        assertEquals("97%", mv.number + "%")
    }

    // MARK: steps — shared precedence + fill; formatting stays per-surface

    @Test
    fun stepsResolved_prefersOnDevice_thenImported_thenEstimate() {
        assertEquals(9000, MetricReads.stepsResolved(daySteps = 9000, imported = 8000, estimated = 5000))
        assertEquals(8000, MetricReads.stepsResolved(daySteps = null, imported = 8000, estimated = 5000))
        assertEquals(5000, MetricReads.stepsResolved(daySteps = null, imported = null, estimated = 5000))
        assertNull(MetricReads.stepsResolved(daySteps = null, imported = null, estimated = null))
    }

    @Test
    fun stepsFrac_isCountOver10k_coerced() {
        assertEquals(0.8, MetricReads.stepsFrac(8000)!!, 1e-9)
        assertEquals(1.0, MetricReads.stepsFrac(14000)!!, 1e-9)   // coerced at the goal
        assertNull(MetricReads.stepsFrac(null))
    }

    // MARK: calories — the tile-vs-card "No Data on one, value on the other" fix

    @Test
    fun caloriesResolved_prefersTheAppDayEstimate_overTheImportedLatest() {
        assertEquals(1961.0, MetricReads.caloriesResolved(dayEstimateKcal = 1961.0, importedLatestKcal = 500.0)!!, 1e-9)
    }

    @Test
    fun caloriesResolved_fallsBackToImportedLatest_whenNoDayEstimate() {
        // The old Your-cards bug: no fallback, so a strap-only day read "No Data" while the tile had a value.
        assertEquals(742.0, MetricReads.caloriesResolved(dayEstimateKcal = null, importedLatestKcal = 742.0)!!, 1e-9)
    }

    @Test
    fun caloriesResolved_null_onlyWhenNeitherSourceHasAValue() {
        assertNull(MetricReads.caloriesResolved(dayEstimateKcal = null, importedLatestKcal = null))
    }

    @Test
    fun calories_bothSurfacesResolveTheSameNumber_soTheyCanNotDisagree() {
        // Reproduces the screenshot: the tile read the day estimate, the row read the (absent) import. With
        // one shared resolution both now land on 1961 — never value-on-one, No-Data-on-the-other.
        val dayEstimate = 1961.0
        val importedLatest: Double? = null   // strap-only setup: no Apple/HC calorie import
        val tile = MetricReads.caloriesResolved(dayEstimate, importedLatest)
        val row = MetricReads.caloriesResolved(dayEstimate, importedLatest)
        assertEquals(tile, row)
        assertEquals(1961.0, tile!!, 1e-9)
    }

    @Test
    fun caloriesFrac_isKcalOver800_coerced() {
        assertEquals(0.5, MetricReads.caloriesFrac(400.0)!!, 1e-9)
        assertEquals(1.0, MetricReads.caloriesFrac(1961.0)!!, 1e-9)   // coerced at the ceiling
        assertNull(MetricReads.caloriesFrac(null))
    }

    @Test
    fun carrySource_isCallerControlled_soASurfaceCanChooseWhichRowItCarries() {
        // The carry ROW is a caller argument: SpO₂ passes its recovery-gated row, the three vitals pass the
        // recovery-independent one. Same function, different carry.
        val today = row(hrv = null)
        assertEquals("40", MetricReads.hrv(today, row(hrv = 40.0)).number)
        assertEquals("44", MetricReads.hrv(today, row(hrv = 44.0)).number)
    }

    // MARK: the deliberate carry choice (Step 2) — the grid's three vitals now read the SAME recovery-
    // independent carry the vitals card + Your-cards row use, so the tile and the card can't disagree.

    @Test
    fun convergedVitalsCarry_makesTheGridTileAgreeWithTheCard_inThe543Scenario() {
        // #543: today is unscored (rollover); last night's recovery was nulled by a re-analysis but its HRV
        // survived; an OLDER night was recovery-scored. The recovery-gated carry the grid USED to pass points
        // at the older night; the recovery-independent vitals carry points at last night.
        val lastNightVitals = row(day = "2026-06-18", hrv = 41.0)   // vitalsDay: recovery nulled, HRV intact
        val olderScored = row(day = "2026-06-17", hrv = 55.0)       // carriedDay: older recovery-scored night
        val today: DailyMetric? = null

        // The old grid carry (carriedDay) would have shown the older night's 55 ms — the tile-vs-card gap.
        assertEquals("55", MetricReads.hrv(today, olderScored).number)
        // The converged carry (vitalsDay) shows last night's own 41 ms, matching the card + row.
        assertEquals("41", MetricReads.hrv(today, lastNightVitals).number)
    }
}
