package com.noop.analytics

import com.noop.data.StepSample
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * StepCounter.kt — the strap's step counter, turned into a day's steps. ONE definition, so the daily
 * total ([AnalyticsEngine.analyzeDay]), the Steps trace ([StepsEstimateEngineTrace.rawCounterTrace]) and
 * the in-app step data check ([StepDayCheck]) cannot disagree about what was counted.
 *
 * WHAT THE STRAP SENDS. A WHOOP 5/MG stamps every stored per-second record with a CUMULATIVE u16 counter
 * (step_motion_counter@57): it climbs while you move, holds while you are still, and wraps at 65536. A
 * day's steps are therefore the sum of the increases between consecutive readings.
 *
 * WHY A GAP IS NOT A REASON TO THROW STEPS AWAY. The counter is cumulative, which is exactly what makes
 * it robust to missing readings: if the stream skips from 09:00 to 09:40, the difference between the two
 * readings is still the steps taken in those forty minutes. The earlier rule accepted an increase only
 * below [MAX_ADJACENT_DELTA] and dropped everything larger as "a sync gap". On an unbroken 1 Hz stream
 * that costs nothing, because no second holds 512 steps. On a stream with holes it discards precisely the
 * stretches with the most walking — a brisk forty minutes is ~4,000 steps, well past 512 — and keeps only
 * the shuffling in between, so the day collapses to a few dozen steps while the counter itself shows
 * thousands.
 *
 * THE RULE.
 *   1. An increase below [MAX_ADJACENT_DELTA], wrap-aware, counts. Unchanged from before, so a day with
 *      an unbroken stream produces exactly the number it always did.
 *   2. A larger increase counts when the counter moved FORWARD without wrapping and no faster than
 *      [MAX_TICKS_PER_SECOND] over the time between the two readings. That is a gap being bridged.
 *   3. Everything else is dropped: a jump faster than a person can move, and any large jump where the
 *      counter went BACKWARDS.
 *
 * WHY BACKWARDS IS NEVER BRIDGED. A strap that restarts begins counting from zero again, and a restart is
 * byte-for-byte indistinguishable from a wrap: both read as "the counter is now lower than it was". The
 * wrap-corrected difference after a restart is an arbitrary number up to 65535, and would be plausible
 * for any gap of a few hours. Forward-only bridging cannot be fooled this way: after a restart the counter
 * is either below the old reading (dropped) or has climbed past it, in which case the difference is LESS
 * than the steps actually taken since the restart. The error is always an under-count, never invented
 * steps. The cost is that a genuine wrap falling inside a gap loses that one gap's steps; the counter
 * wraps roughly once per 65,000 steps, so that is rare and bounded.
 */
object StepCounter {

    /** An increase below this counts without looking at the clock: consecutive records, wrap included. */
    const val MAX_ADJACENT_DELTA = 512

    /**
     * Fastest average rate, in counter ticks per second, at which a gap may be bridged. A hard sprint is
     * a little over 3 steps per second and nobody holds it across a gap of minutes, so 4 leaves headroom
     * for a counter that ticks slightly more often than once per step without admitting a jump no wrist
     * could have produced.
     */
    const val MAX_TICKS_PER_SECOND = 4.0

    /** Two readings further apart than this are separated by a hole in the stream, for [Tally.gaps]. */
    const val GAP_SECONDS = 120L

    /**
     * What a day's readings added up to, and how. [rawTicks] is the total before the user's
     * ticks-per-step divisor; the remaining fields describe how the stream looked, for the trace and the
     * step data check.
     */
    data class Tally(
        /** Readings considered (the day's samples, time-ordered). */
        val readings: Int,
        /** Counter ticks counted: adjacent increases plus bridged gaps. */
        val rawTicks: Long,
        /** Increases counted under rule 1 (below [MAX_ADJACENT_DELTA]). */
        val keptDeltas: Int,
        /** Smallest / largest increase counted under rule 1, or null when there was none. */
        val minKeptDelta: Int?,
        val maxKeptDelta: Int?,
        /** Gaps bridged under rule 2, and the ticks they contributed (already inside [rawTicks]). */
        val bridgedDeltas: Int,
        val bridgedTicks: Long,
        /** Large jumps dropped under rule 3. */
        val droppedDeltas: Int,
        /** Holes longer than [GAP_SECONDS] between consecutive readings, and the longest of them. */
        val gaps: Int,
        val longestGapSeconds: Long,
        /** Seconds the stream actually covered: the span between readings that were NOT a hole apart. */
        val coveredSeconds: Long,
    )

    /**
     * Add up one day's readings. [sorted] must already be filtered to the day and ordered by time —
     * callers do both, because which day a reading belongs to depends on the user's timezone and that is
     * not this function's business. Fewer than two readings add up to nothing.
     */
    fun tally(sorted: List<StepSample>): Tally {
        var rawTicks = 0L
        var kept = 0
        var minKept = Int.MAX_VALUE
        var maxKept = Int.MIN_VALUE
        var bridged = 0
        var bridgedTicks = 0L
        var dropped = 0
        var gaps = 0
        var longestGap = 0L
        var covered = 0L
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val seconds = cur.ts - prev.ts
            if (seconds > GAP_SECONDS) {
                gaps += 1
                longestGap = max(longestGap, seconds)
            } else if (seconds > 0) {
                covered += seconds
            }
            val forward = cur.counter - prev.counter          // negative when the counter went backwards
            val wrapped = forward and 0xFFFF                  // the same increase, read as a u16 wrap
            when {
                wrapped == 0 -> Unit
                wrapped < MAX_ADJACENT_DELTA -> {
                    rawTicks += wrapped
                    kept += 1
                    if (wrapped < minKept) minKept = wrapped
                    if (wrapped > maxKept) maxKept = wrapped
                }
                forward >= MAX_ADJACENT_DELTA && seconds > 0 && forward <= MAX_TICKS_PER_SECOND * seconds -> {
                    rawTicks += forward
                    bridged += 1
                    bridgedTicks += forward
                }
                else -> dropped += 1
            }
        }
        return Tally(
            readings = sorted.size,
            rawTicks = rawTicks,
            keptDeltas = kept,
            minKeptDelta = if (kept > 0) minKept else null,
            maxKeptDelta = if (kept > 0) maxKept else null,
            bridgedDeltas = bridged,
            bridgedTicks = bridgedTicks,
            droppedDeltas = dropped,
            gaps = gaps,
            longestGapSeconds = longestGap,
            coveredSeconds = covered,
        )
    }

    /**
     * Counter ticks to steps. The counter measures motion ticks rather than validated steps and can run
     * high, so the total is divided by the user's ticks-per-step ([UserProfile.stepTicksPerStep], 1.0 =
     * as counted). The divisor is floored at 0.5 so a bad setting can at most double the total, never
     * explode it. Returns null when nothing was counted or the result rounds to zero: "no steps measured"
     * is shown as no value, never as a real zero.
     */
    fun steps(rawTicks: Long, ticksPerStep: Double): Int? {
        if (rawTicks <= 0L) return null
        val scaled = (rawTicks.toDouble() / max(ticksPerStep, 0.5)).roundToLong()
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (scaled > 0) scaled else null
    }
}
