package com.noop.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Baselines.kt — personal rolling baselines per nightly metric.
 *
 * Faithful Kotlin port of StrandAnalytics/Baselines.swift (verified on macOS),
 * itself ported from server/ingest/app/analysis/baselines.py.
 *
 * Two paths are provided:
 *   1. Winsorized EWMA (the production model): robust, recency-weighted center
 *      with an EWMA-of-absolute-deviation spread tracker, cold-start gating, hard
 *      outlier rejection, and Winsor clamping. This is [update] / [foldHistory].
 *   2. Trailing-window mean/SD (the task's "trailing 30-day mean/SD"): a simple,
 *      auditable rolling mean and sample SD over the trailing N valid nights.
 *      This is [rollingMeanSD]. Useful for explainability and cross-checking.
 *
 * Both produce a [BaselineState] so RecoveryScorer can consume either uniformly.
 *
 * The value types ([MetricCfg], [BaselineStatus], [BaselineState], [Deviation])
 * are defined in AnalyticsModels.kt and intentionally NOT redefined here. All
 * `ts` are wall-clock unix SECONDS (Long) elsewhere; baselines work on per-night
 * scalar values and carry no timestamps.
 *
 * Outputs are APPROXIMATE and not medical advice.
 */

/** Personal rolling baselines. Mirrors Swift `Baselines` (an enum used as a namespace). */
object Baselines {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants (baselines.py)
    // ─────────────────────────────────────────────────────────────────────────

    /** Winsorization clamp: fold only within ±WINSOR_K × spread. */
    const val winsorK: Double = 3.0

    /** Hard-reject gate: drop the night if > HARD_OUTLIER_K × spread away. */
    const val hardOutlierK: Double = 5.0

    /** Minimum valid nights before "provisionally" trusted. */
    const val minNightsSeed: Int = 4

    /** Minimum valid nights before fully trusted. */
    const val minNightsTrust: Int = 14

    /** Missing-night count after which a baseline is marked stale. */
    const val staleDays: Int = 14

    // ─────────────────────────────────────────────────────────────────────────
    // Early-life anti-anchoring (Reddit HRV report) — mirrors Baselines.swift
    // ─────────────────────────────────────────────────────────────────────────
    //
    // The original model seeds the center on the first valid night with spread pinned at the
    // floor, then becomes "usable" at minNightsSeed. If those first few nights read artificially
    // HIGH (a common cold-start artefact), three things compounded to lock the baseline high for
    // ~2-3 weeks: (a) the seed fixed the mean high while spread sat at the floor; (b) the hard
    // outlier gate then REJECTED the user's genuine LOWER nights (a true 54ms vs an anchored ~85ms
    // baseline is >5× the floor spread → "seen but not folded"); (c) the still-tight spread made
    // the z-score hypersensitive, crushing Charge to 1-2.
    //
    // The fix is conservative: during the baseline's EARLY life let reality pull the center down
    // quickly, THEN settle to the normal long-term smoothing. Long-term behaviour (after
    // earlyAdaptNights, once spread has lifted) is byte-identical to before.

    /** Valid-night count below which the baseline is "young": fast center adaptation + suspended
     *  hard-outlier gate. Chosen so convergence happens in days, not weeks. */
    const val earlyAdaptNights: Int = 8

    /** Center half-life (nights) used while the baseline is young — much faster than halfLifeB. */
    const val earlyHalfLifeB: Double = 3.0

    /** Multiplier on spread for the Winsor clamp while young, so an honest lower night isn't clamped
     *  flat against a floor-tight band before the spread has had a chance to widen. */
    const val earlySpreadInflate: Double = 2.5

    /** SharedPreferences key for the manual HRV-baseline recalibration epoch (epoch SECONDS).
     *  0 / absent = no recalibration. Written by the Settings "Recalibrate HRV baseline" button.
     *  EXACT same key string as the iOS UserDefaults key. */
    const val hrvBaselineEpochKey: String = "noop.hrvBaselineEpoch"

    /** SharedPreferences key for the manual RECOVERY-baseline recalibration epoch (epoch SECONDS).
     *  0 / absent = no recalibration. The Charge-wide sibling of [hrvBaselineEpochKey]: HRV (the
     *  dominant Charge driver) re-anchors on its own epoch today, while the resting-HR / respiration /
     *  skin-temp baselines that also feed Charge re-anchor on THIS epoch. The Settings "Recalibrate
     *  Charge baseline" button writes BOTH keys to now (see [recalibrateRecoveryBaselines]) so the whole
     *  Charge build-up restarts cleanly. EXACT same key string as the iOS UserDefaults key. */
    const val recoveryBaselineEpochKey: String = "noop.recoveryBaselineEpoch"

    /** Default per-metric configurations (HRV, resting HR, respiration, skin temp). */
    val metricCfg: Map<String, MetricCfg> = mapOf(
        "hrv" to MetricCfg(
            minVal = 5.0, maxVal = 250.0, floorSpread = 5.0,
            halfLifeB = 14.0, halfLifeS = 21.0,
        ),
        "resting_hr" to MetricCfg(
            minVal = 30.0, maxVal = 120.0, floorSpread = 2.0,
            halfLifeB = 14.0, halfLifeS = 21.0,
        ),
        "resp" to MetricCfg(
            minVal = 4.0, maxVal = 40.0, floorSpread = 0.5,
            halfLifeB = 14.0, halfLifeS = 21.0,
        ),
        "skin_temp" to MetricCfg(
            minVal = 20.0, maxVal = 42.0, floorSpread = 0.3,
            halfLifeB = 14.0, halfLifeS = 21.0,
        ),
    )

    /** Convenience accessor for the standard HRV config. */
    val hrvCfg: MetricCfg get() = metricCfg.getValue("hrv")

    /** Convenience accessor for the standard resting-HR config. */
    val restingHRCfg: MetricCfg get() = metricCfg.getValue("resting_hr")

    /** Convenience accessor for the standard respiration config. */
    val respCfg: MetricCfg get() = metricCfg.getValue("resp")

    /** Convert a half-life in nights to an EWMA smoothing factor. */
    internal fun lambda(halfLife: Double): Double = 1.0 - 0.5.pow(1.0 / halfLife)

    internal fun computeStatus(nValid: Int, nightsSinceUpdate: Int): BaselineStatus {
        if (nightsSinceUpdate > staleDays && nValid >= minNightsSeed) return BaselineStatus.STALE
        if (nValid < minNightsSeed) return BaselineStatus.CALIBRATING
        if (nValid < minNightsTrust) return BaselineStatus.PROVISIONAL
        return BaselineStatus.TRUSTED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Winsorized EWMA update (production model)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Incorporate one new nightly value into the baseline state.
     *
     * - `state == null`: seed the first night.
     * - `value == null` or out-of-range: skip-and-hold (carry forward).
     * - hard outlier (> HARD_OUTLIER_K × spread): seen but not folded.
     * - otherwise: Winsorized EWMA center + EWMA-abs-dev spread update.
     */
    fun update(state: BaselineState?, value: Double?, cfg: MetricCfg): BaselineState {
        val lb = lambda(cfg.halfLifeB)
        val ls = lambda(cfg.halfLifeS)

        // First night ever.
        if (state == null) {
            if (value != null && cfg.minVal <= value && value <= cfg.maxVal) {
                return BaselineState(
                    baseline = value, spread = cfg.floorSpread, nValid = 1,
                    nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
                )
            }
            val seed = (cfg.minVal + cfg.maxVal) / 2.0
            return BaselineState(
                baseline = seed, spread = cfg.floorSpread, nValid = 0,
                nightsSinceUpdate = 1, status = BaselineStatus.CALIBRATING,
            )
        }

        // Missing night: skip-and-hold.
        if (value == null) {
            val m = state.nightsSinceUpdate + 1
            return BaselineState(
                baseline = state.baseline, spread = state.spread,
                nValid = state.nValid, nightsSinceUpdate = m,
                status = computeStatus(state.nValid, m),
            )
        }

        // Step 0: sanity gate — physiologically implausible → skip-and-hold.
        if (!(cfg.minVal <= value && value <= cfg.maxVal)) {
            val m = state.nightsSinceUpdate + 1
            return BaselineState(
                baseline = state.baseline, spread = state.spread,
                nValid = state.nValid, nightsSinceUpdate = m,
                status = computeStatus(state.nValid, m),
            )
        }

        // Is the baseline still "young"? While young we adapt faster and suspend the hard-outlier
        // gate so genuine lower nights are never discarded before the spread reflects them. Tied to
        // the valid-night count (NOT spread): a long flat history is settled even though its spread
        // never lifted off the floor, and must still reject a wild one-off outlier.
        val isYoung = state.nValid < earlyAdaptNights

        // Hard outlier rejection (only once seeded AND no longer young): seen, but not folded.
        // Suspending this during early life is the core anti-anchoring fix — a high seed with a
        // floor-tight spread would otherwise reject the user's real, lower readings as "outliers"
        // (a true 54ms vs an anchored ~90ms baseline is >5× the floor spread).
        if (state.nValid >= minNightsSeed && !isYoung) {
            val dev = abs(value - state.baseline)
            if (dev > hardOutlierK * state.spread) {
                return BaselineState(
                    baseline = state.baseline, spread = state.spread,
                    nValid = state.nValid, nightsSinceUpdate = 0,
                    status = computeStatus(state.nValid, 0),
                )
            }
        }

        // First real value after a None-placeholder seed: treat as clean first night.
        if (state.nValid == 0) {
            return BaselineState(
                baseline = value, spread = cfg.floorSpread, nValid = 1,
                nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
            )
        }

        // Step 1: Winsorized EWMA update.
        // While young, widen the clamp band (inflate the effective spread) so an honest lower night
        // isn't clamped flat against a floor-tight band, and use the faster early center half-life so
        // the center tracks reality in days. Both relax to the normal values once settled.
        val effSpread = if (isYoung) state.spread * earlySpreadInflate else state.spread
        val effLb = if (isYoung) lambda(earlyHalfLifeB) else lb
        val lo = state.baseline - winsorK * effSpread
        val hi = state.baseline + winsorK * effSpread
        val clamped = max(lo, min(hi, value))
        val newBaseline = effLb * clamped + (1.0 - effLb) * state.baseline

        // Spread uses the UNCLAMPED value so true deviations are tracked.
        val absDev = abs(value - newBaseline)
        val newSpread = max(cfg.floorSpread, ls * absDev + (1.0 - ls) * state.spread)
        val newN = state.nValid + 1

        return BaselineState(
            baseline = newBaseline, spread = newSpread, nValid = newN,
            nightsSinceUpdate = 0,
            status = computeStatus(newN, 0),
        )
    }

    /**
     * Replay an ordered sequence of nightly values (oldest first) to build state.
     * `null` entries are treated as missing nights (skip-and-hold).
     */
    fun foldHistory(values: List<Double?>, cfg: MetricCfg): BaselineState {
        var state: BaselineState? = null
        for (v in values) state = update(state, v, cfg)
        state?.let { return it }
        val seed = (cfg.minVal + cfg.maxVal) / 2.0
        return BaselineState(
            baseline = seed, spread = cfg.floorSpread, nValid = 0,
            nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
        )
    }

    /**
     * Replay an ordered sequence of nightly values (oldest first) to build state, honouring a manual
     * recalibration [baselineEpoch] (epoch SECONDS; 0 = no recalibration).
     *
     * [dayKeys] runs parallel to [values] ("yyyy-MM-dd", same order/length). Any night whose day
     * STARTS (UTC) before [baselineEpoch] is ignored entirely (NOT a skip-and-hold — it is dropped,
     * so the baseline re-seeds from the first on-or-after-epoch night). This lets the user reset a
     * baseline that anchored too high: tap "Recalibrate HRV baseline" in Settings (which writes
     * `now-seconds` to the [hrvBaselineEpochKey] pref) and the Charge baseline re-learns from tonight.
     *
     * When [baselineEpoch] <= 0 (the default / no recalibration) this is byte-identical to the plain
     * [foldHistory]. The caller reads the persisted epoch from SharedPreferences (the analytics layer
     * is Context-free) — exact same key string as the iOS UserDefaults key. Mirrors the Swift overload.
     */
    fun foldHistory(
        values: List<Double?>,
        dayKeys: List<String>,
        cfg: MetricCfg,
        baselineEpoch: Double,
    ): BaselineState {
        if (baselineEpoch <= 0.0) return foldHistory(values, cfg)

        var state: BaselineState? = null
        for (i in values.indices) {
            // Drop (not skip-and-hold) any night dated before the recalibration epoch.
            if (i < dayKeys.size) {
                val dayStart = runCatching {
                    java.time.LocalDate.parse(dayKeys[i])
                        .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
                }.getOrNull()
                if (dayStart != null && dayStart < baselineEpoch) continue
            }
            state = update(state, values[i], cfg)
        }
        state?.let { return it }
        val seed = (cfg.minVal + cfg.maxVal) / 2.0
        return BaselineState(
            baseline = seed, spread = cfg.floorSpread, nValid = 0,
            nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prefix fold (causal baselines)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The baseline state as it stood BEFORE each night, i.e. element `i` is the state after folding
     * entries `0..<i` only. Same replay [update] runs — the returned element `i` is exactly what
     * [foldHistory] over `values.take(i)` would produce — so this adds no new baseline maths, it only
     * exposes the intermediate states the fold already walks through.
     *
     * WHY: Charge for a night must be scored against who the person was BEFORE that night. Folding the
     * whole history into one terminal state and scoring every day against it makes a past day's score
     * depend on nights that happened after it, so the score moved every time the engine re-ran (the
     * ~15-minute rescore). With the per-night state the score is causal AND idempotent: re-scoring an
     * already-scored day yields the identical number, so a repeat pass is a genuine no-op.
     *
     * Element 0 is the empty-history seed (nValid = 0, CALIBRATING), so the first nights are refused by
     * the caller's `usable` gate rather than scored against themselves — the honest cold start.
     *
     * @param values ordered nightly values, oldest first; nulls are missing nights (skip-and-hold).
     * @return a list the SAME length as [values].
     */
    fun foldHistoryPrefix(values: List<Double?>, cfg: MetricCfg): List<BaselineState> {
        val out = ArrayList<BaselineState>(values.size)
        var state: BaselineState? = null
        for (v in values) {
            out.add(state ?: emptyState(cfg))
            state = update(state, v, cfg)
        }
        return out
    }

    /**
     * [foldHistoryPrefix] honouring a manual recalibration [baselineEpoch] (epoch SECONDS; 0 = none),
     * exactly as the day-keyed [foldHistory] overload does: a night whose day STARTS (UTC) before the
     * epoch is DROPPED (not skip-and-hold), so the baseline re-seeds from the first on-or-after-epoch
     * night. A dropped night still gets an entry in the returned list (the state carried at that point),
     * so the result stays parallel to [values].
     *
     * With [baselineEpoch] <= 0 this is byte-identical to the plain [foldHistoryPrefix].
     */
    fun foldHistoryPrefix(
        values: List<Double?>,
        dayKeys: List<String>,
        cfg: MetricCfg,
        baselineEpoch: Double,
    ): List<BaselineState> {
        if (baselineEpoch <= 0.0) return foldHistoryPrefix(values, cfg)

        val out = ArrayList<BaselineState>(values.size)
        var state: BaselineState? = null
        for (i in values.indices) {
            out.add(state ?: emptyState(cfg))
            if (i < dayKeys.size) {
                val dayStart = runCatching {
                    java.time.LocalDate.parse(dayKeys[i])
                        .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
                }.getOrNull()
                if (dayStart != null && dayStart < baselineEpoch) continue
            }
            state = update(state, values[i], cfg)
        }
        return out
    }

    /** The no-history state both folds fall back to: centred on the config midpoint, spread at the
     *  floor, nValid = 0 (so [BaselineState.usable] is false and no score is produced). Identical to
     *  what [foldHistory] returns for an empty input. */
    internal fun emptyState(cfg: MetricCfg): BaselineState = BaselineState(
        baseline = (cfg.minVal + cfg.maxVal) / 2.0,
        spread = cfg.floorSpread,
        nValid = 0,
        nightsSinceUpdate = 0,
        status = BaselineStatus.CALIBRATING,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Deviation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute z / delta / ratio / in-normal-range for a value vs a baseline.
     * z uses (value − baseline) / (1.253 × spread); 1.253 converts EWMA-abs-dev
     * to an approximate Gaussian σ (E[|X−μ|] = σ·√(2/π) ≈ σ/1.253).
     */
    fun deviation(value: Double, state: BaselineState): Deviation {
        val sigma = max(1.253 * state.spread, 1e-9)
        val z = (value - state.baseline) / sigma
        val delta = value - state.baseline
        val ratio = if (state.baseline != 0.0) (value / state.baseline - 1.0) else 0.0
        return Deviation(z = z, delta = delta, ratio = ratio, inNormalRange = abs(z) <= 1.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trailing-window mean/SD (simple, auditable)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rolling personal baseline from the trailing [window] valid nights, as a
     * plain mean and sample SD (ddof=1). This is the task's "trailing 30-day
     * mean/SD" path: no recency weighting, maximally explainable.
     *
     * Physiologically implausible values (outside cfg bounds) and nulls are
     * dropped. The spread returned is stored in the SAME internal units the
     * Winsor EWMA uses (abs-dev space), i.e. SD / 1.253, so that [deviation]
     * recovers the intended Gaussian σ unchanged.
     *
     * @param values ordered nightly values (oldest → newest); nulls allowed.
     * @param cfg metric config (bounds + floor spread).
     * @param window number of trailing valid nights to use (default 30).
     */
    fun rollingMeanSD(values: List<Double?>, cfg: MetricCfg, window: Int = 30): BaselineState {
        val valid = values.mapNotNull { v ->
            if (v != null && cfg.minVal <= v && v <= cfg.maxVal) v else null
        }
        if (valid.isEmpty()) {
            val seed = (cfg.minVal + cfg.maxVal) / 2.0
            return BaselineState(
                baseline = seed, spread = cfg.floorSpread, nValid = 0,
                nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
            )
        }
        val trailing = valid.takeLast(window)
        val n = trailing.size
        val mean = trailing.sum() / n.toDouble()

        val sd: Double
        if (n >= 2) {
            var ss = 0.0
            for (v in trailing) {
                val d = v - mean
                ss += d * d
            }
            sd = sqrt(ss / (n - 1).toDouble())
        } else {
            // Single sample: no dispersion estimate; fall back to the σ floor.
            sd = cfg.floorSpread * 1.253
        }

        // Apply the σ floor in σ-space, then convert to internal abs-dev space.
        val sigmaFloored = max(cfg.floorSpread, sd)
        val spreadInternal = sigmaFloored / 1.253

        return BaselineState(
            baseline = mean, spread = spreadInternal, nValid = n,
            nightsSinceUpdate = 0,
            status = computeStatus(n, 0),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manual recalibration ("Recalibrate Charge baseline")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recalibrate every baseline that feeds Charge: drop the anchor so the ~4-night build-up restarts
     * from [nowSeconds]. This is the single source of truth behind the Settings "Recalibrate Charge
     * baseline" button — it writes [nowSeconds] (epoch SECONDS, whole) to BOTH the HRV epoch and the
     * recovery epoch, so HRV (the dominant driver, already wired) and the resting-HR / respiration /
     * skin-temp baselines re-anchor together. It does NOT delete any stored day: only the day from
     * which the baselines re-learn moves. After this the next foldHistory re-seeds from the first
     * on-or-after-[nowSeconds] night, so Today honestly shows the calibrating/building state again.
     *
     * The analytics layer is Context-free, so the caller passes in the prefs editor. Epochs are stored
     * as whole seconds in a Long (SharedPreferences has no putDouble; the readers do getLong→toDouble),
     * matching the "epoch SECONDS" the keys document and the iOS UserDefaults values byte-for-byte.
     */
    fun recalibrateRecoveryBaselines(editor: android.content.SharedPreferences.Editor, nowSeconds: Long) {
        editor.putLong(hrvBaselineEpochKey, nowSeconds)
        editor.putLong(recoveryBaselineEpochKey, nowSeconds)
    }

    /**
     * Remove the anchor entirely, so every night ever recorded counts towards the baselines again.
     *
     * The inverse of [recalibrateRecoveryBaselines], and the reason that one is reversible at all: an
     * anchor only ever changed which night the fold STARTS from, it never deleted a night. Writing 0 to
     * both keys therefore restores the full history exactly — there is nothing to recover, because
     * nothing was lost. Same two keys, so this and the anchor can never disagree about what is set.
     */
    fun clearRecoveryBaselineAnchor(editor: android.content.SharedPreferences.Editor) {
        editor.putLong(hrvBaselineEpochKey, 0L)
        editor.putLong(recoveryBaselineEpochKey, 0L)
    }
}
