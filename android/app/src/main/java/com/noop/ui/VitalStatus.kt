package com.noop.ui

import androidx.compose.ui.graphics.Color
import com.noop.analytics.Baselines
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric

// MARK: - Shared per-vital banding → goodness colour (Step B2 of the colour rework)
//
// The Vital Signs screen colours a vital by STATE (green on baseline → amber → red) via VitalBands +
// [Palette.goodnessColor]. This object exposes the SAME banding so the Today surfaces (Key-Metrics tiles,
// Your-cards rows, Recovery-vitals card) colour a vital identically — a metric can never band one way on
// Today and another on Vital Signs. It mirrors HealthScreen.vitalsFor's per-vital band() calls verbatim:
// same population ranges, same personal configs, same calendar-padded strictly-prior history.

object VitalStatus {

    /**
     * The 0..1 goodness of a banded vital for [day] against the [days] history (oldest→newest), or null
     * when the key isn't a banded vital or there's no reading. History is taken STRICTLY BEFORE the
     * displayed day and calendar-padded, exactly like the Vital Signs screen, so a wear gap ages the
     * baseline the same way.
     */
    fun goodness(key: String, day: DailyMetric?, days: List<DailyMetric>): Double? {
        val todayKey = day?.day
        val history = days.filter { row -> todayKey == null || row.day < todayKey }
        fun series(selector: (DailyMetric) -> Double?): List<Double?> =
            VitalBands.calendarSeries(history.map { it.day to selector(it) })
        return when (key) {
            "hrv" -> VitalBands.band(day?.avgHrv, series { it.avgHrv }, 40.0..120.0, Baselines.hrvCfg).goodness
            "rhr" -> VitalBands.band(
                day?.restingHr?.toDouble(), series { it.restingHr?.toDouble() }, 40.0..60.0, Baselines.restingHRCfg,
            ).goodness
            "resp" -> VitalBands.band(day?.respRateBpm, series { it.respRateBpm }, 12.0..20.0, Baselines.respCfg).goodness
            // Population-only on purpose (an absolute <95% floor is meaningful regardless of baseline; no cfg).
            "spo2" -> VitalBands.band(day?.spo2Pct, emptyList(), 95.0..100.0, null).goodness
            "skin" -> {
                val skin = day?.skinTempDevC ?: return null
                val absolute = VitalBands.isAbsoluteSkinTemp(skin)
                VitalBands.band(
                    skin,
                    VitalBands.skinTempHistory(skin, series { it.skinTempDevC }),
                    if (absolute) 33.0..36.0 else -0.6..0.6,
                    if (absolute) Baselines.metricCfg.getValue("skin_temp") else VitalBands.skinTempDeviationCfg,
                ).goodness
            }
            else -> null
        }
    }

    /**
     * The wellness COLOUR for a vital (green→amber→red by state), or [fallback] when [byState] is off, the
     * metric isn't a banded vital, or there's no reading yet. [fallback] is the surface's identity tint.
     * [byState] is the user's "colour Today vitals by state" setting (NoopPrefs.vitalStateColours): off
     * (default) keeps the calm identity colours; on opts into the baseline-relative gradient.
     */
    fun color(key: String, day: DailyMetric?, days: List<DailyMetric>, fallback: Color, byState: Boolean): Color =
        if (!byState) fallback else goodness(key, day, days)?.let { Palette.goodnessColor(it) } ?: fallback
}
