package com.noop.analytics

/*
 * ScoreConfidence.kt — per-score certainty tier for Charge / Effort / Rest.
 *
 * Faithful Kotlin mirror of StrandAnalytics/ScoreConfidence.swift. Keep the cases,
 * raw strings, and the derive() thresholds byte-identical to Swift — cross-platform
 * parity tests enforce it.
 *
 * Each daily score (Charge = recovery, Effort = strain, Rest = sleep_performance)
 * carries a tier so a sparse 5/MG day reads truthfully instead of faking a number:
 *
 *   CALIBRATING — the score cannot honestly compute yet: the personal baseline isn't
 *                 usable (Charge), there's no in-bed data (Rest), or no HR window
 *                 (Effort). Usually paired with a null score.
 *   BUILDING    — usable but thin: e.g. < ~7 nights of baseline, or a 5/MG day whose
 *                 HR is mostly PPG-derived.
 *   SOLID       — full inputs present.
 *
 * Surfaced as a small label/dot under each score; the metric itself stays nil-honest
 * where it can't compute.
 */
enum class ScoreConfidence(val raw: String) {
    CALIBRATING("calibrating"),
    BUILDING("building"),
    SOLID("solid");

    companion object {

        /** Nights of usable baseline below which a present score is only "building". */
        const val buildingNightsThreshold: Int = 7

        /**
         * Charge (recovery) confidence.
         * - null score → CALIBRATING (HRV baseline not usable yet, or no driver).
         * - score present but the HRV baseline has < [buildingNightsThreshold] valid
         *   nights (still PROVISIONAL, not TRUSTED) → BUILDING.
         * - otherwise → SOLID.
         *
         * @param score the computed Charge, or null when the scorer refused.
         * @param hrvBaseline the HRV baseline driving the cold-start gate (nullable).
         */
        fun forCharge(score: Double?, hrvBaseline: BaselineState?): ScoreConfidence {
            if (score == null || hrvBaseline == null || !hrvBaseline.usable) return CALIBRATING
            return if (hrvBaseline.nValid < buildingNightsThreshold || !hrvBaseline.trusted) {
                BUILDING
            } else {
                SOLID
            }
        }

        /**
         * Effort (strain) confidence.
         * - null score (no HR window / invalid HRR) → CALIBRATING.
         * - score present but backed by fewer than [solidHrSamples] HR samples (a thin
         *   window, typically a 5/MG day leaning on PPG-derived HR) → BUILDING.
         * - otherwise → SOLID.
         *
         * @param score the computed Effort, or null.
         * @param hrSampleCount number of HR samples in the day's window.
         */
        fun forEffort(score: Double?, hrSampleCount: Int): ScoreConfidence {
            if (score == null) return CALIBRATING
            return if (hrSampleCount < solidHrSamples) BUILDING else SOLID
        }

        /** HR samples below which Effort is "building" (≈1 h at 1 Hz over a thin day). */
        const val solidHrSamples: Int = 3600

        /**
         * Rest (sleep performance) confidence — mirrors Swift `ScoreConfidence.rest`. The tier
         * reflects how complete THIS night's Rest inputs are, not the sleep-need history length.
         * - no in-bed session → CALIBRATING.
         * - a session exists but has no staged sleep (no deep/REM — e.g. a sparse night), so
         *   restorative + efficiency aren't real inputs → BUILDING.
         * - a session with staged sleep present → SOLID.
         *
         * @param hasSession whether the day has at least one matched in-bed session.
         * @param hasStagedSleep whether the night has staged sleep (deep + REM seconds > 0).
         */
        fun forRest(hasSession: Boolean, hasStagedSleep: Boolean): ScoreConfidence {
            if (!hasSession) return CALIBRATING
            return if (hasStagedSleep) SOLID else BUILDING
        }

        // ── H9 stage low-confidence (restorative-share floor on a high-efficiency night) ──────────────

        /**
         * Restorative (deep+REM) share of asleep time below which staging is treated as LOW-CONFIDENCE on
         * an otherwise high-efficiency night. A genuine well-structured adult night sits ~40–50% deep+REM;
         * a near-zero restorative share on a night that ALSO scored high efficiency is far more likely a
         * staging miss (the EEG-free classifier's weakest link) than a real night with no deep or REM — so
         * we flag the LOW CONFIDENCE rather than fake stages or tank Rest. Mirrors Swift
         * `restorativeLowConfidenceShare`. (#H9)
         */
        const val restorativeLowConfidenceShare: Double = 0.10

        /** Efficiency above which the restorative-share floor applies. A low-efficiency (fragmented) night
         *  legitimately carries less deep/REM, so the floor only flags the suspicious high-efficiency case.
         *  Mirrors Swift `highEfficiencyThreshold`. (#H9) */
        const val highEfficiencyThreshold: Double = 0.85

        /**
         * Rest confidence WITH the H9 stage-quality check. Starts from [forRest], then DOWNGRADES a [SOLID]
         * tier to [BUILDING] (low-confidence) when the night is high-efficiency yet its restorative
         * (deep+REM) share is below [restorativeLowConfidenceShare] — a likely staging miss, surfaced
         * honestly without inventing stages or distorting the Rest score. [CALIBRATING]/[BUILDING] from the
         * base call are returned unchanged. Engine output only; the UI surfaces the tier later. Mirrors Swift
         * `rest(hasSession:hasStagedSleep:asleepSeconds:restorativeSeconds:efficiency:)`. (#H9)
         */
        fun forRest(
            hasSession: Boolean,
            hasStagedSleep: Boolean,
            asleepSeconds: Double,
            restorativeSeconds: Double,
            efficiency: Double,
        ): ScoreConfidence {
            val base = forRest(hasSession, hasStagedSleep)
            if (base != SOLID || asleepSeconds <= 0.0) return base
            val restorativeShare = restorativeSeconds / asleepSeconds
            return if (efficiency >= highEfficiencyThreshold && restorativeShare < restorativeLowConfidenceShare) {
                BUILDING   // high-efficiency night with near-zero deep+REM → low-confidence staging
            } else {
                base
            }
        }
    }
}
