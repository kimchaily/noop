package com.noop.analytics

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StepCounter] is the one place the strap's cumulative step counter is added up, shared by the daily
 * total, the Steps trace and the in-app step data check. These tests pin the three rules it applies and,
 * above all, the property that makes changing it safe: on an unbroken stream it produces exactly the
 * number the old rule did.
 */
class StepCounterTest {

    private fun at(ts: Long, counter: Int) = StepSample(deviceId = "my-whoop", ts = ts, counter = counter)

    /** The rule this replaced: every wrap-aware increase below 512 counts, everything else is dropped. */
    private fun oldRule(sorted: List<StepSample>): Long {
        var total = 0L
        for (i in 1 until sorted.size) {
            val delta = (sorted[i].counter - sorted[i - 1].counter) and 0xFFFF
            if (delta in 1 until 512) total += delta
        }
        return total
    }

    @Test
    fun anUnbrokenStreamCountsExactlyAsBefore() {
        // A whole day at 1 Hz with every kind of second in it: still, strolling, running, and a wrap
        // partway through. No second holds 512 steps, so nothing is ever bridged and the totals match.
        val readings = ArrayList<StepSample>()
        var counter = 64_000
        for (second in 0 until 86_400) {
            val pace = when {
                second % 3_600 < 1_800 -> 0                 // half of every hour still
                second % 3_600 < 3_000 -> second % 3        // strolling: 0, 1 or 2 a second
                else -> 3                                   // running
            }
            counter = (counter + pace) and 0xFFFF
            readings.add(at(second.toLong(), counter))
        }
        val tally = StepCounter.tally(readings)
        assertEquals(oldRule(readings), tally.rawTicks)
        assertEquals(0, tally.bridgedDeltas)
        assertEquals(0, tally.droppedDeltas)
        assertEquals(0, tally.gaps)
        assertEquals(86_399L, tally.coveredSeconds)
    }

    @Test
    fun aHoleIsBridgedWhenTheCounterClimbedNoFasterThanAPersonMoves() {
        // 40 minutes of silence, 4,000 higher at the far end: a brisk walk.
        val tally = StepCounter.tally(listOf(at(0, 100), at(1, 102), at(2_401, 4_102), at(2_402, 4_103)))
        assertEquals(2L + 4_000L + 1L, tally.rawTicks)
        assertEquals(1, tally.bridgedDeltas)
        assertEquals(4_000L, tally.bridgedTicks)
        assertEquals(1, tally.gaps)
        assertEquals(2_400L, tally.longestGapSeconds)
        assertEquals(2L, tally.coveredSeconds)
    }

    @Test
    fun theBridgingRateIsABoundNotAnAllowance() {
        // Exactly 4 ticks a second across the gap is the ceiling and counts; one tick more does not.
        val atTheLimit = StepCounter.tally(listOf(at(0, 0), at(1_000, 4_000)))
        assertEquals(4_000L, atTheLimit.rawTicks)
        val overTheLimit = StepCounter.tally(listOf(at(0, 0), at(1_000, 4_001)))
        assertEquals(0L, overTheLimit.rawTicks)
        assertEquals(1, overTheLimit.droppedDeltas)
    }

    @Test
    fun aJumpTooFastToBeStepsIsDroppedHoweverItArrives() {
        // 900 in one second, and 900 in one minute: neither is a wrist.
        assertEquals(0L, StepCounter.tally(listOf(at(0, 100), at(1, 1_000))).rawTicks)
        assertEquals(0L, StepCounter.tally(listOf(at(0, 100), at(60, 1_000))).rawTicks)
    }

    @Test
    fun aWrapBetweenNeighbouringReadingsStillCounts() {
        // 65,530 -> 10 a second later is 16 steps, not a restart: small wrap-aware increases are
        // unchanged from the old rule.
        val tally = StepCounter.tally(listOf(at(0, 65_530), at(1, 10)))
        assertEquals(16L, tally.rawTicks)
        assertEquals(1, tally.keptDeltas)
    }

    @Test
    fun backwardsAcrossAHoleIsNeverBridged() {
        // A restart and a wrap inside a hole read the same. 30,000 -> 500 over six hours would be a
        // believable 36,036 "steps" if the wrap were trusted. It is dropped.
        val tally = StepCounter.tally(listOf(at(0, 30_000), at(6 * 3_600L, 500)))
        assertEquals(0L, tally.rawTicks)
        assertEquals(1, tally.droppedDeltas)
        assertEquals(0, tally.bridgedDeltas)
    }

    @Test
    fun fewerThanTwoReadingsAddUpToNothing() {
        assertEquals(0L, StepCounter.tally(emptyList()).rawTicks)
        assertEquals(0L, StepCounter.tally(listOf(at(0, 500))).rawTicks)
        assertEquals(1, StepCounter.tally(listOf(at(0, 500))).readings)
    }

    @Test
    fun keptRangeIsAbsentWhenNothingSmallWasKept() {
        val bridgedOnly = StepCounter.tally(listOf(at(0, 0), at(3_600, 2_000)))
        assertNull(bridgedOnly.minKeptDelta)
        assertNull(bridgedOnly.maxKeptDelta)
        val kept = StepCounter.tally(listOf(at(0, 0), at(1, 3), at(2, 4)))
        assertEquals(1, kept.minKeptDelta)
        assertEquals(3, kept.maxKeptDelta)
    }

    @Test
    fun ticksBecomeStepsThroughTheDivisor() {
        assertEquals(120, StepCounter.steps(120L, 1.0))
        assertEquals(60, StepCounter.steps(120L, 2.0))
        assertEquals(61, StepCounter.steps(121L, 2.0))       // 60.5 rounds to nearest
        assertEquals(240, StepCounter.steps(120L, 0.1))      // divisor floored at 0.5: doubles at most
    }

    @Test
    fun nothingCountedIsNoValueNotZero() {
        assertNull(StepCounter.steps(0L, 1.0))
        assertNull(StepCounter.steps(1L, 3.0))               // 0.33 rounds to zero: still no value
    }
}
