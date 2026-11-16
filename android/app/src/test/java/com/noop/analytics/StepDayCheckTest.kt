package com.noop.analytics

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepDayCheck] exists so a low Steps number can be explained from one screenshot. Each test is one of
 * the situations that all look identical on the Today tile — a small number — and asserts that the check
 * tells them apart.
 */
class StepDayCheckTest {

    private val day = "2026-01-02"

    private fun at(ts: Long, counter: Int) = StepSample(deviceId = "my-whoop", ts = ts, counter = counter)

    /** An unbroken 1 Hz stream of [seconds] readings climbing [perSecond] each. */
    private fun steady(seconds: Int, perSecond: Int, from: Int = 1_000): List<StepSample> =
        (0 until seconds).map { at(it.toLong(), from + it * perSecond) }

    @Test
    fun aPlainStrapDayHasNothingToReport() {
        val readings = steady(seconds = 3_600, perSecond = 1)
        val check = StepDayCheck.build(day, stored = 3_599, phone = 4_000, estimate = 3_000, readings, 1.0)
        assertEquals(StepDayCheck.Source.STRAP, check.source)
        assertEquals(3_599, check.shown)
        assertEquals(3_599, check.recounted)
        assertEquals(3_599, check.counterClimb)
        assertTrue(check.findings.toString(), check.findings.isEmpty())
    }

    @Test
    fun theTilePrefersStrapThenPhoneThenEstimate() {
        fun source(stored: Int?, phone: Int?, estimate: Int?) =
            StepDayCheck.build(day, stored, phone, estimate, emptyList(), 1.0).let { it.source to it.shown }
        assertEquals(StepDayCheck.Source.STRAP to 10, source(10, 20, 30))
        assertEquals(StepDayCheck.Source.PHONE to 20, source(null, 20, 30))
        assertEquals(StepDayCheck.Source.ESTIMATE to 30, source(null, null, 30))
        assertEquals(StepDayCheck.Source.NONE to null, source(null, null, null))
    }

    @Test
    fun aDayWithNoCounterReadingsSaysSoAndNamesWhatIsShownInstead() {
        // The strap handed over nothing, so the tile is showing the phone's count. A phone left on a desk
        // reads 52 on a day of walking, and nothing on the tile says it is the phone's number.
        val check = StepDayCheck.build(day, stored = null, phone = 52, estimate = null, emptyList(), 1.0)
        assertEquals(52, check.shown)
        assertEquals(StepDayCheck.Source.PHONE, check.source)
        assertTrue(check.streamLine, check.streamLine.startsWith("No counter readings"))
        assertTrue(check.findings.any { it.contains("phone's count") })
        assertNull(check.recounted)
        assertNull(check.counterClimb)
    }

    @Test
    fun aCalibrationDivisorIsNamed() {
        // The counter moved 2,970 and the tile reads 99, because Step calibration is set to 30.
        val readings = steady(seconds = 2_971, perSecond = 1)
        val check = StepDayCheck.build(day, stored = 99, phone = null, estimate = null, readings, 30.0)
        assertEquals(99, check.recounted)
        assertEquals(2_970, check.counterClimb)
        assertTrue(check.findings.toString(), check.findings.any { it.contains("divides the counter by 30.0") })
    }

    @Test
    fun holesInTheStreamAreCountedAndTheirStepsReported() {
        // Two bursts an hour apart with 5,000 steps walked in between.
        val readings = steady(seconds = 60, perSecond = 1) +
            (0 until 60).map { at(3_660L + it, 1_059 + 5_000 + it) }
        val check = StepDayCheck.build(day, stored = 118, phone = null, estimate = null, readings, 1.0)
        assertEquals(59 + 5_000 + 59, check.recounted)
        assertTrue(check.findings.toString(), check.findings.any { it.contains("1 hole") && it.contains("5,000 steps") })
        // The stored row still holds the number from before the holes were bridged, and the check says so
        // rather than leaving two different totals on screen unexplained.
        assertTrue(check.findings.any { it.contains("Stored for this day: 118") && it.contains("5,118") })
    }

    @Test
    fun droppedJumpsAreReported() {
        val readings = listOf(at(0, 100), at(1, 101), at(3_601, 40), at(3_602, 41))
        val check = StepDayCheck.build(day, stored = 2, phone = null, estimate = null, readings, 1.0)
        assertEquals(2, check.recounted)
        assertTrue(check.findings.toString(), check.findings.any { it.startsWith("1 jump not counted") })
    }

    @Test
    fun theStreamLineStatesReadingsCoverageAndCounterRange() {
        val check = StepDayCheck.build(day, 7_199, null, null, steady(seconds = 7_200, perSecond = 1), 1.0)
        assertEquals("7,200 readings covering 2.0 h, counter 1000 to 8199 (+7,199)", check.streamLine)
        assertFalse(check.streamLine.contains("—"))
    }

    @Test
    fun durationsReadInTheLargestSensibleUnit() {
        assertEquals("45 s", StepDayCheck.duration(45))
        assertEquals("12 min", StepDayCheck.duration(12 * 60L + 30))
        assertEquals("3.5 h", StepDayCheck.duration(3 * 3_600L + 1_800))
    }
}
