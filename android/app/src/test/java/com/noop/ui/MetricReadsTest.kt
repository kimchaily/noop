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
    ) = DailyMetric(deviceId = "my-whoop", day = day, avgHrv = hrv, restingHr = rhr, respRateBpm = resp)

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

    @Test
    fun carrySource_isCallerControlled_soSurfacesCanStillDifferOnWhichRowTheyCarry() {
        // The grid carries `carriedDay`; the card carries `vitalsDay`. Same function, different carry arg —
        // this is the intentionally-preserved difference the next step will reconcile.
        val today = row(hrv = null)
        val gridCarry = row(hrv = 40.0)
        val cardCarry = row(hrv = 44.0)
        assertEquals("40", MetricReads.hrv(today, gridCarry).number)
        assertEquals("44", MetricReads.hrv(today, cardCarry).number)
    }
}
