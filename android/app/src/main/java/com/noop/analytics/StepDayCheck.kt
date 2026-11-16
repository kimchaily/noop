package com.noop.analytics

import com.noop.data.StepSample
import java.util.Locale
import kotlin.math.abs

/**
 * StepDayCheck.kt — one day's Steps number, taken apart: where it came from and what it was built on.
 *
 * WHY THIS EXISTS. The Steps tile shows a single number that can come from three places — the strap's
 * own counter, the phone's count, or the motion estimate — and from the tile alone there is no telling
 * which one is on screen, nor why it reads low. A day showing 52 could be a strap that sent no counter
 * readings, a counter stream full of holes, a calibration divisor someone nudged, or simply the phone's
 * count from a day the phone stayed on the desk. Each needs a different fix, and guessing between them
 * means shipping the wrong one.
 *
 * So this lays the inputs out next to the result, in the app, where they can be read without exporting
 * the database. It reuses [StepCounter] for the adding-up and the tile's own precedence for the source,
 * so what it reports is what the app actually did rather than a second opinion.
 *
 * Pure: no clock, no I/O. The caller reads the rows and hands them over.
 */
object StepDayCheck {

    /** Which of the three sources the Steps tile shows for a day, in the tile's own order of preference. */
    enum class Source(val label: String) {
        STRAP("Strap counter"),
        PHONE("Phone"),
        ESTIMATE("Motion estimate"),
        NONE("No data"),
    }

    data class Day(
        /** yyyy-MM-dd, local. */
        val day: String,
        /** What the Steps tile shows for this day, and which source that is. */
        val shown: Int?,
        val source: Source,
        /** The strap total stored for the day when it was last calculated. */
        val stored: Int?,
        /** The phone's count (Health Connect / Apple Health), and the motion estimate, when present. */
        val phone: Int?,
        val estimate: Int?,
        /** Counter readings the strap handed over for the day, and the counter on the first and last. */
        val readings: Int,
        val firstCounter: Int?,
        val lastCounter: Int?,
        /** How [StepCounter] adds those readings up, and the steps that makes at [ticksPerStep]. */
        val tally: StepCounter.Tally,
        val recounted: Int?,
        val ticksPerStep: Double,
    ) {
        /**
         * How far the counter moved between the day's first and last reading, wrap-aware. This is the
         * most the day can hold: when it is far above [recounted] the readings in between were not
         * counted, and when it is itself small the strap genuinely registered little.
         */
        val counterClimb: Int?
            get() {
                val first = firstCounter ?: return null
                val last = lastCounter ?: return null
                return (last - first) and 0xFFFF
            }

        /** One line of raw facts about the strap's stream for the day. */
        val streamLine: String
            get() = when {
                readings == 0 -> "No counter readings from the strap (a WHOOP 4.0 sends none)"
                readings == 1 -> "1 counter reading (two are needed to count)"
                else -> {
                    val hours = tally.coveredSeconds / 3600.0
                    "${grouped(readings)} readings covering ${String.format(Locale.US, "%.1f", hours)} h, " +
                        "counter $firstCounter to $lastCounter (+${grouped(counterClimb ?: 0)})"
                }
            }

        /**
         * What stands out about the day, in plain sentences, most decisive first. Empty when the number
         * on the tile is simply what the strap counted.
         */
        val findings: List<String>
            get() {
                val out = ArrayList<String>()
                when (source) {
                    Source.NONE -> out.add("Nothing counted steps on this day.")
                    Source.PHONE ->
                        out.add("Showing your phone's count: the strap has no step total for this day.")
                    Source.ESTIMATE ->
                        out.add("Showing the motion estimate: neither the strap nor your phone counted.")
                    Source.STRAP -> Unit
                }
                if (readings >= 2) {
                    if (abs(ticksPerStep - 1.0) > 0.05) {
                        out.add(
                            "Step calibration divides the counter by " +
                                "${String.format(Locale.US, "%.1f", ticksPerStep)} (Profile, Step calibration).",
                        )
                    }
                    if (tally.gaps > 0) {
                        val recovered = StepCounter.steps(tally.bridgedTicks, ticksPerStep) ?: 0
                        out.add(
                            "${tally.gaps} hole${if (tally.gaps == 1) "" else "s"} in the strap's stream, " +
                                "the longest ${duration(tally.longestGapSeconds)}. " +
                                "${grouped(recovered)} steps counted across them.",
                        )
                    }
                    if (tally.droppedDeltas > 0) {
                        out.add(
                            "${tally.droppedDeltas} jump${if (tally.droppedDeltas == 1) "" else "s"} not " +
                                "counted: faster than a person moves, or the counter ran backwards.",
                        )
                    }
                    if (stored != recounted) {
                        out.add(
                            "Stored for this day: ${stored?.let { grouped(it) } ?: "nothing"}. Counted from " +
                                "the readings now: ${recounted?.let { grouped(it) } ?: "nothing"}. The stored " +
                                "value catches up at the next recalculation.",
                        )
                    }
                }
                return out
            }
    }

    /**
     * Take one day apart. [readings] are the strap's counter samples for the day, already filtered to it
     * and time-ordered. [stored] is the strap total on the day's stored row; [phone] and [estimate] are
     * the two fallbacks. The source follows the Steps tile exactly: strap, then phone, then estimate.
     */
    fun build(
        day: String,
        stored: Int?,
        phone: Int?,
        estimate: Int?,
        readings: List<StepSample>,
        ticksPerStep: Double,
    ): Day {
        val tally = StepCounter.tally(readings)
        val shown = stored ?: phone ?: estimate
        val source = when {
            stored != null -> Source.STRAP
            phone != null -> Source.PHONE
            estimate != null -> Source.ESTIMATE
            else -> Source.NONE
        }
        return Day(
            day = day,
            shown = shown,
            source = source,
            stored = stored,
            phone = phone,
            estimate = estimate,
            readings = readings.size,
            firstCounter = readings.firstOrNull()?.counter,
            lastCounter = readings.lastOrNull()?.counter,
            tally = tally,
            recounted = if (readings.size < 2) null else StepCounter.steps(tally.rawTicks, ticksPerStep),
            ticksPerStep = ticksPerStep,
        )
    }

    /** 12,345 — the same grouping the Steps tile's detail uses, locale-free so a screenshot reads the same. */
    internal fun grouped(n: Int): String =
        if (abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"

    /** A hole's length in the largest sensible unit: "45 s", "12 min", "3.5 h". */
    internal fun duration(seconds: Long): String = when {
        seconds < 120 -> "$seconds s"
        seconds < 2 * 3600 -> "${seconds / 60} min"
        else -> "${String.format(Locale.US, "%.1f", seconds / 3600.0)} h"
    }
}
