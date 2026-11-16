package com.noop.analytics

import com.noop.data.StepSample

// StepsEstimateEngineTrace.kt - Kotlin twin of StepsEstimateEngine+Trace.swift. The Steps test-mode traces.
//
// Two pure, side-effect-free twins for the two ways Choop produces a step number:
//
//  1. calibrationTrace(...) - the WHOOP-4 motion-volume path. Reports each calibration day's motion VOLUME
//     and phone reference count, then the fitted (or manual) calibration state (k / sampleDays / confidence
//     / manual) by reusing StepsEstimateEngine.calibrate VERBATIM, so the trace can never disagree with the
//     coefficient the Settings/Steps screen shows; when withheld it names the status (the "Need N more days"
//     reason), the same status the tile renders.
//
//  2. rawCounterTrace(...) - the WHOOP 5/MG raw path. Reports the cumulative step_motion_counter series and
//     how StepCounter.tally added it up: the small wrap-aware increases it kept, the gaps it bridged (a
//     forward climb across a hole in the stream, no faster than a person can move), the jumps it dropped
//     (too fast, or backwards: a restart reads exactly like a wrap), and how many holes the stream had.
//     It calls the SAME StepCounter the daily total calls, so the trace and the daily steps can never
//     diverge.
//
// No clock, no IO, no PII (counts and ratios only). The Steps test mode gates each call behind
// TestCentre.active(STEPS) at the call site (IntelligenceEngine); when the mode is off neither is ever
// called, so there is zero cost. The calibration lines are byte-aligned with the Swift line shapes; the
// raw-counter lines carry the bridged / stream fields the Swift twin does not have yet. No em-dashes.

object StepsEstimateEngineTrace {

    private fun r2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /**
     * The WHOOP-4 motion-volume calibration trace. Given the per-day calibration points (each a motion volume
     * + a phone reference step count) and the optional manual override, it logs one `stepsCal point` line per
     * usable day, then the calibration outcome - built by reusing [StepsEstimateEngine.calibrate] VERBATIM
     * (so k / sampleDays / confidence / manual match the stored coefficient), or the [StepsEstimateEngine.status]
     * line naming why the fit was withheld. Mirrors the Swift StepsEstimateEngine.calibrationTrace.
     */
    fun calibrationTrace(
        points: List<StepsEstimateEngine.CalibrationPoint>,
        manualOverride: Double? = null,
    ): List<String> {
        val lines = ArrayList<String>()

        // Per-usable-day points: the SAME filter the fit applies, so the trace shows exactly the days that voted.
        val usable = points.filter {
            it.motion >= StepsEstimateEngine.MIN_MOTION_FOR_FIT && it.steps > 0
        }
        for (p in usable) {
            val ratio = if (p.motion > 0) p.steps / p.motion else 0.0
            lines.add(
                "stepsCal point motion=${r2(p.motion)} phoneRef=${p.steps.toInt()} " +
                    "ratio=${r2(ratio)} (steps/motion votes weighted by motion)",
            )
        }

        // The calibration outcome, read from calibrate(...) verbatim so it matches the stored coefficient.
        val cal = StepsEstimateEngine.calibrate(points, manualOverride)
        if (cal != null && (usable.size >= StepsEstimateEngine.MIN_CALIBRATION_DAYS || cal.manual)) {
            lines.add(
                "stepsCal fit k=${r2(cal.coefficient)} sampleDays=${cal.sampleDays} " +
                    "confidence=${r2(cal.confidence)} manual=${cal.manual} " +
                    "(k = motion-weighted median of steps/motion)",
            )
        } else {
            // Withheld: name the status the tile shows, via status(...) verbatim (SAME usable-day filter).
            when (val status = StepsEstimateEngine.status(points, manualOverride)) {
                is StepsEstimateEngine.CalibrationStatus.NeedsMoreDays ->
                    lines.add(
                        "stepsCal withheld reason=needsMoreDays have=${status.have} need=${status.need} " +
                            "(no usable auto-fit and no manual k)",
                    )
                is StepsEstimateEngine.CalibrationStatus.Manual ->
                    lines.add(
                        "stepsCal fit k=${r2(status.coefficient)} sampleDays=${status.sampleDays} " +
                            "confidence=1.0 manual=true (user-set k)",
                    )
                is StepsEstimateEngine.CalibrationStatus.Calibrated ->
                    lines.add(
                        "stepsCal fit k=${r2(status.coefficient)} sampleDays=${status.sampleDays} " +
                            "confidence=${r2(status.confidence)} manual=false " +
                            "(k = motion-weighted median of steps/motion)",
                    )
            }
        }
        return lines
    }

    /**
     * The WHOOP 5/MG raw-counter trace for one day. Runs the SAME [StepCounter.tally] that
     * [AnalyticsEngine.analyzeDay] runs over the cumulative step_motion_counter series (the time-ordered
     * records filtered to the LOCAL day) and the SAME [StepCounter.steps] scaling. Reports the counter series
     * length, the kept / dropped / bridged counts, the holes in the stream, the raw tick total and the scaled
     * steps - the SAME value the day's steps carry.
     */
    fun rawCounterTrace(
        daySteps: List<StepSample>,
        dayKey: String,
        tzOffsetSeconds: Long,
        ticksPerStep: Double,
    ): List<String> {
        // The SAME filter + sort: keep only this LOCAL day's samples, time-ordered.
        val sorted = daySteps
            .filter { AnalyticsEngine.dayString(it.ts, tzOffsetSeconds) == dayKey }
            .sortedBy { it.ts }

        val lines = ArrayList<String>()
        // #810: a WHOOP 4.0 sends NO raw step counter over BLE at all, so `daySteps` is empty for it; its
        // steps are MOTION-ESTIMATED (the calibrationTrace path), not counted. Emitting the bare
        // "counterSamples=0 (need >=2 for a delta)" line made a 4.0 export read as BROKEN. When there is
        // no counter sample at all, say so honestly so the trace reflects the model, not a fault. (A 5/MG
        // with a single counter sample still falls through to the "need >=2" line: it HAS a counter, just
        // one read this window.)
        if (sorted.isEmpty()) {
            lines.add(
                "stepsRaw day=$dayKey counterSamples=0 noRawCounter " +
                    "(no step counter on this device; steps are motion-estimated, e.g. WHOOP 4.0)",
            )
            return lines
        }
        if (sorted.size < 2) {
            lines.add("stepsRaw day=$dayKey counterSamples=${sorted.size} (need >=2 for a delta)")
            return lines
        }

        // The production adding-up, verbatim: this is the call analyzeDay makes.
        val tally = StepCounter.tally(sorted)

        val firstCounter = sorted.first().counter
        val lastCounter = sorted.last().counter
        lines.add(
            "stepsRaw day=$dayKey counterSamples=${sorted.size} " +
                "firstCounter=$firstCounter lastCounter=$lastCounter (cumulative u16 @57)",
        )
        lines.add(
            "stepsRaw deltas kept=${tally.keptDeltas} dropped=${tally.droppedDeltas} " +
                "bridged=${tally.bridgedDeltas} bridgedTicks=${tally.bridgedTicks} " +
                "(kept = increase<${StepCounter.MAX_ADJACENT_DELTA}; bridged = forward climb across a gap; " +
                "dropped = too fast or backwards)",
        )
        if (tally.minKeptDelta != null && tally.maxKeptDelta != null) {
            lines.add(
                "stepsRaw keptRange min=${tally.minKeptDelta} max=${tally.maxKeptDelta} " +
                    "(each = (cur-prev)&0xFFFF, wrap-aware)",
            )
        }
        // How whole the stream was. A day with long holes is the case the bridging exists for; a day with
        // none is an unbroken stream, where bridged is always 0 and the total is the plain sum.
        lines.add(
            "stepsRaw stream gaps=${tally.gaps} longestGapSec=${tally.longestGapSeconds} " +
                "coveredSec=${tally.coveredSeconds} (gap = readings more than ${StepCounter.GAP_SECONDS}s apart)",
        )

        // The scaled total, the SAME call analyzeDay makes for the day's steps (ticks / ticksPerStep,
        // floored at 0.5 so a bad pref can at most double, never explode, the total).
        // L7: production returns null when the total rounds to 0, so a tiny rawTicks yields NO steps for
        // the day. Render "none" (not 0) so the trace matches the missing headline rather than implying a
        // real zero-step measurement.
        val scaledText = StepCounter.steps(tally.rawTicks, ticksPerStep)?.toString() ?: "none"
        lines.add(
            "stepsRaw total rawTicks=${tally.rawTicks} ticksPerStep=${r2(ticksPerStep)} " +
                "scaledSteps=$scaledText (steps_est for the day)",
        )
        return lines
    }
}

/**
 * Pure values for the Steps live-readout panel. Kotlin twin of the Swift StepsReadout. Each parses the
 * STEPS-tagged log tail the Steps test-mode emitters write. No state, no IO, no em-dashes. (Android defers
 * the Compose readout panel for ALL modes, matching the existing split; this twin exists for parity + tests.)
 */
object StepsReadout {

    /** Today's steps for the `stepsToday` id: the most recent scaled-steps figure in the tagged tail (the
     *  5/MG `scaledSteps=` or the WHOOP-4 `stepsEst ... steps=`). null when no step line is present yet. */
    fun stepsToday(taggedTail: List<String>): Int? {
        for (line in taggedTail.asReversed()) {
            val n = intField(line, "scaledSteps=")
            if (n != null) return n
            if (line.contains("stepsEst ")) {
                val e = intField(line, "steps=")
                if (e != null) return e
            }
        }
        return null
    }

    /** Calibration state for the `calibrationState` id: the most recent calibration outcome fragment (the
     *  WHOOP-4 `stepsCal fit ...` or `stepsCal withheld reason=...`). null when no calibration line yet. */
    fun calibrationState(taggedTail: List<String>): String? {
        for (line in taggedTail.asReversed()) {
            val fit = line.indexOf("stepsCal fit ")
            if (fit >= 0) {
                val frag = line.substring(fit + "stepsCal fit ".length).takeWhile { it != '(' }.trim()
                if (frag.isNotEmpty()) return frag
            }
            val withheld = line.indexOf("stepsCal withheld reason=")
            if (withheld >= 0) {
                val frag = line.substring(withheld + "stepsCal withheld reason=".length)
                    .takeWhile { it != '(' }.trim()
                if (frag.isNotEmpty()) return "not calibrated ($frag)"
            }
        }
        return null
    }

    /** Parse a `key=<int>` field out of a line (value runs to the next space). null when absent/non-numeric. */
    internal fun intField(line: String, key: String): Int? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val token = line.substring(i + key.length).takeWhile { it != ' ' }
        return token.toIntOrNull()
    }
}
