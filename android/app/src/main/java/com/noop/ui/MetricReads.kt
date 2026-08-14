package com.noop.ui

import com.noop.data.DailyMetric
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Shared metric reads (Step 1 of the "one catalog, many renderers" refactor)
//
// The Today screen surfaces the three overnight vitals — HRV, Resting HR and Respiratory — in THREE
// different shapes: the fixed "Recovery vitals" card ([HeroMetricRows]), the customisable "Key Metrics"
// tile grid ([MetricGrid]'s KeyTileData) and the "Your cards" WHOOP rows ([dashboardCardValue] /
// [dashboardCardFraction]). Until now each surface re-implemented the SAME field selection, rounding and
// vessel-fill maths inline, so the reads could (and per #543 repeatedly DID) drift apart between the
// tile and the card.
//
// This object is the single place those three vitals are resolved. Each surface calls the same function
// and renders the result in its own shape, so a change to a field, a unit, a rounding rule or a fill
// ceiling now lands everywhere at once.
//
// KNOWN remaining difference, intentionally preserved by this step: the CARRY ROW still differs per
// surface. The Key-Metrics grid passes the recovery-gated `carriedDay` (lastScoredRecoveryDay); the
// vitals card and the Your-cards rows pass the recovery-INDEPENDENT `vitalsDay` (lastVitalsRow, #543
// follow-up). The carry SOURCE is therefore a caller-supplied argument here, so this refactor is
// byte-for-byte behaviour-preserving. Reconciling which carry is correct is the next step, not this one.

/**
 * One resolved metric read, shape-agnostic. [number] is the bare formatted value (e.g. "72", "14.2") or
 * null when there's no reading yet — callers append the [unit] in whatever way their shape wants (the
 * vitals card joins "72 ms"; the tile keeps value and unit separate). [frac] is the 0..1 liquid-vessel
 * fill, or null for an empty vessel.
 */
internal data class MetricValue(
    val number: String?,
    val unit: String,
    val frac: Double?,
) {
    /** True when a real reading is present (so callers can dim / substitute their own "No Data" string). */
    val hasValue: Boolean get() = number != null
}

/**
 * Resolves the three overnight vitals from a today-first row plus a fallback carry row. The value read is
 * always `today's own field, else the carry's field` — identical across every Today surface; only the carry
 * ROW is chosen by the caller (see the file header). Formatting, units and vessel-fill ceilings live here
 * once so the tile and the card can no longer disagree.
 */
internal object MetricReads {

    /** HRV in whole milliseconds; vessel fills against a 120 ms ceiling. */
    fun hrv(today: DailyMetric?, carry: DailyMetric?): MetricValue {
        val v = today?.avgHrv ?: carry?.avgHrv
        return MetricValue(
            number = v?.let { it.roundToInt().toString() },
            unit = "ms",
            frac = v?.let { (it / 120.0).coerceIn(0.0, 1.0) },
        )
    }

    /** Resting heart rate in whole bpm; vessel fills against a 100 bpm ceiling. */
    fun restingHr(today: DailyMetric?, carry: DailyMetric?): MetricValue {
        val v = today?.restingHr ?: carry?.restingHr
        return MetricValue(
            number = v?.toString(),
            unit = "bpm",
            frac = v?.let { (it / 100.0).coerceIn(0.0, 1.0) },
        )
    }

    /** Respiratory rate to 1 dp (breaths per minute); vessel fills against a 24 rpm ceiling. */
    fun respiratory(today: DailyMetric?, carry: DailyMetric?): MetricValue {
        val v = today?.respRateBpm ?: carry?.respRateBpm
        return MetricValue(
            number = v?.let { String.format(Locale.US, "%.1f", it) },
            unit = "rpm",
            frac = v?.let { (it / 24.0).coerceIn(0.0, 1.0) },
        )
    }

    /**
     * Blood oxygen saturation as a whole percent; vessel fills against a 100% ceiling. [number] is the bare
     * percent ("97"), so the Key-Metrics tile keeps value and "%" unit split while the Your-cards row joins
     * them inline ("97%"). SpO₂ is intentionally on the recovery-gated carry (unlike the three vitals), so
     * callers still pass whichever gated row they use.
     */
    fun bloodOxygen(today: DailyMetric?, carry: DailyMetric?): MetricValue {
        val v = today?.spo2Pct ?: carry?.spo2Pct
        return MetricValue(
            number = v?.let { String.format(Locale.US, "%.0f", it) },
            unit = "%",
            frac = v?.let { (it / 100.0).coerceIn(0.0, 1.0) },
        )
    }

    /**
     * The Steps precedence shared by every surface: on-device count → imported total → estimate (#107/#150).
     * The FORMATTED string differs per surface (grouped "11,200" on the WHOOP row, bare "11200" on the compact
     * tile), so this returns the resolved COUNT and each caller formats it; the fill ceiling is [stepsFrac].
     */
    fun stepsResolved(daySteps: Int?, imported: Int?, estimated: Int?): Int? = daySteps ?: imported ?: estimated

    /** Steps vessel fill against a 10 000-step daily-goal ceiling. */
    fun stepsFrac(steps: Int?): Double? = steps?.let { (it / 10000.0).coerceIn(0.0, 1.0) }

    /**
     * Active-energy (kcal) resolution shared by the Key-Metrics tile and the Your-cards row: the app's own
     * per-day estimate first, then the imported Apple Health / Health Connect latest as a fallback. The two
     * surfaces previously read DIFFERENT sources with no shared fallback — the tile read the day estimate
     * (activeKcalEst) while the row read only the Apple/HC latest — so on a strap-only setup (no Apple import)
     * the row showed "No Data" while the tile showed a real value on the same screen. Resolving both here
     * fixes that. Formatting stays per surface (bare on the tile, grouped on the row).
     */
    fun caloriesResolved(dayEstimateKcal: Double?, importedLatestKcal: Double?): Double? =
        dayEstimateKcal ?: importedLatestKcal

    /** Calories vessel fill against an 800 kcal active-energy ceiling. */
    fun caloriesFrac(kcal: Double?): Double? = kcal?.let { (it / 800.0).coerceIn(0.0, 1.0) }

    // MARK: - The six metrics the Key-Metrics grid gained from the Your-cards dashboard
    //
    // Each was already resolved INLINE inside `dashboardCardValue` / `dashboardCardFraction`. Lifting them
    // here first, unchanged, is what lets the new tile and the existing card share one read instead of the
    // grid growing a second copy that drifts (the #543 failure mode this file exists to prevent). Every
    // format below is byte-for-byte what the card already printed — no displayed number changes.

    /**
     * Skin-temperature deviation from baseline, signed so +/- reads honestly ("+0.3°"). The degree glyph is
     * part of [MetricValue.number] (as the card always printed it) and [MetricValue.unit] is empty, so the
     * tile shows the same single token. No vessel fill: a deviation has no natural 0..1 ceiling.
     */
    fun skinTemp(today: DailyMetric?, carry: DailyMetric?): MetricValue {
        val v = today?.skinTempDevC ?: carry?.skinTempDevC
        return MetricValue(
            number = v?.let { String.format(Locale.US, "%+.1f°", it) },
            unit = "",
            frac = null,
        )
    }

    /** Sleep duration as "7h 12m"; vessel fills against an 8 h (480 min) night. */
    fun sleep(today: DailyMetric?, carry: DailyMetric?): MetricValue {
        val m = today?.totalSleepMin ?: carry?.totalSleepMin
        return MetricValue(
            number = m?.let { val t = it.roundToInt(); "${t / 60}h ${t % 60}m" },
            unit = "",
            frac = m?.let { (it / 480.0).coerceIn(0.0, 1.0) },
        )
    }

    /**
     * Stress as a whole 0–3 autonomic-load score; vessel fills against the 3 ceiling. A null score is NOT
     * "no data" here — Stress is baseline-relative, so callers substitute their own "Calibrating" placeholder
     * (see the card's STRESS_CALIBRATING note) rather than a bare dash.
     */
    fun stress(score: Double?): MetricValue =
        MetricValue(
            number = score?.let { it.roundToInt().toString() },
            unit = "",
            frac = score?.let { (it / 3.0).coerceIn(0.0, 1.0) },
        )

    /** Vitality as a whole 0–100 wellness score; vessel fills against the 100 ceiling. */
    fun vitality(score: Double?): MetricValue =
        MetricValue(
            number = score?.let { it.roundToInt().toString() },
            unit = "",
            frac = score?.let { (it / 100.0).coerceIn(0.0, 1.0) },
        )

    /**
     * Fitness age in whole years. The vessel sits at a FIXED half-fill when a score exists: fitness age has
     * no "full" to fill toward (younger is better, so a ceiling would read backwards), and a half vessel says
     * "present" without implying a rank. Mirrors the iOS `liquidCard` frac for this card.
     */
    fun fitnessAge(years: Double?): MetricValue =
        MetricValue(
            number = years?.let { it.roundToInt().toString() },
            unit = "yrs",
            frac = if (years != null) 0.5 else null,
        )

    /**
     * Logged fluid against the day's goal, in litres to 1 dp ("1.2 / 3.2 L"). Always resolves — a fresh day
     * reads "0.0 / 3.2 L" — because the goal is always derivable from the profile. No vessel fill: the value
     * already carries its own progress, and the card has never filled one.
     */
    fun hydration(totalMl: Double, goalMl: Int): MetricValue =
        MetricValue(
            number = String.format(Locale.US, "%.1f / %.1f L", totalMl / 1000.0, goalMl / 1000.0),
            unit = "",
            frac = null,
        )
}
