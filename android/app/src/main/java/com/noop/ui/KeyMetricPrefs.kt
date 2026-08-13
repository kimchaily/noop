package com.noop.ui

import android.content.Context

// MARK: - Editable Key-Metrics layout (#251)
//
// The Today screen's "Key Metrics" grid was a fixed list of ten tiles in one order. This lets the user
// choose WHICH tiles show and in WHAT order, with the default being the original order so nothing changes
// for anyone who never opens the editor. Persistence is display-only — no metric is computed or stored
// differently; this just decides which of the already-computed tiles render and in what sequence.
//
// Stored as a single comma-joined string of metric keys in SharedPreferences ("today.keyMetrics"), the
// same mechanism every other Android preference uses. Mirrors the macOS KeyMetricPrefs.swift +
// @AppStorage("today.keyMetrics"). Unknown keys are dropped on read so a removed tile can't crash, and
// any known key missing from the saved list is treated as disabled (the editor re-lists it).

/**
 * One of the Today screen's Key-Metric tiles. The [raw] is the stable persisted identifier — keep it
 * byte-identical to the macOS `KeyMetric` enum so a backup/restore reads the same layout on either OS.
 */
enum class KeyMetric(val raw: String, val title: String) {
    CHARGE("charge", "Charge"),
    EFFORT("effort", "Effort"),
    REST("rest", "Rest"),
    HRV("hrv", "HRV"),
    RESTING_HR("restingHr", "Resting HR"),
    BLOOD_OXYGEN("bloodOxygen", "Blood Oxygen"),
    RESPIRATORY("respiratory", "Respiratory"),
    STEPS("steps", "Steps"),
    WEIGHT("weight", "Weight"),
    CALORIES("calories", "Calories"),

    // The six metrics the "Your cards" dashboard carried but this grid did not. The two lists were built at
    // different times over the SAME already-loaded values, so a metric being tile-able or card-able was an
    // accident of history rather than a data limit — Sleep / Stress / Skin Temp / Vitality / Fitness Age /
    // Hydration were readable here all along. Raws are byte-identical to the matching [DashboardCard] raw
    // (and so to the iOS enum), so one metric means one id on every surface and every OS.
    SLEEP("sleep", "Sleep"),
    STRESS("stress", "Stress"),
    SKIN_TEMP("skinTemp", "Skin Temp"),
    VITALITY("vitality", "Vitality"),
    FITNESS_AGE("fitnessAge", "Fitness Age"),
    HYDRATION("hydration", "Hydration");

    companion object {
        fun fromRaw(raw: String?): KeyMetric? = entries.firstOrNull { it.raw == raw }

        /**
         * The default grid order — the original ten in their hard-coded order, then the six that joined them.
         * Every tile is ON by default: the grid already folds everything past [METRICS_COLLAPSED_CAP] behind
         * "Show all metrics", so a full default costs one tap to browse rather than a trip to the editor. A
         * user who already saved a layout keeps it untouched (their stored string wins); only an unset layout
         * reads this.
         */
        val defaultOrder: List<KeyMetric> = listOf(
            CHARGE, EFFORT, REST, HRV, RESTING_HR,
            BLOOD_OXYGEN, RESPIRATORY, STEPS, WEIGHT, CALORIES,
            SLEEP, STRESS, SKIN_TEMP, VITALITY, FITNESS_AGE, HYDRATION,
        )
    }
}

/**
 * Display-only persistence for the Key-Metrics layout. Holds an ORDERED list of the enabled tiles; a tile
 * not in the list is hidden. SharedPreferences isn't reactive, so the Today screen reads this once into
 * remembered state (like the other prefs) and re-reads on the recomposition the editor's write triggers.
 * Mirrors the macOS KeyMetricPrefs (@AppStorage "today.keyMetrics").
 */
object KeyMetricPrefs {
    private const val KEY_LAYOUT = "today.keyMetrics"

    /** The enabled tiles in display order. An empty/unset string yields the full default order. */
    fun enabled(context: Context): List<KeyMetric> =
        decodeEnabled(NoopPrefs.of(context).getString(KEY_LAYOUT, null))

    /** Persist the enabled tiles in order. Disabled tiles are simply omitted from the stored string. */
    fun setEnabled(context: Context, metrics: List<KeyMetric>) {
        NoopPrefs.of(context).edit().putString(KEY_LAYOUT, encode(metrics)).apply()
    }

    /** Encode an ordered list of enabled tiles into the stored comma-joined string. */
    fun encode(metrics: List<KeyMetric>): String = metrics.joinToString(",") { it.raw }

    /**
     * Decode the stored string into an ordered list of enabled tiles. An empty/unset string yields the
     * full default order (so a fresh install shows every tile). Unknown tokens are ignored, duplicates
     * collapsed; this returns ONLY the enabled tiles in their saved order.
     */
    fun decodeEnabled(raw: String?): List<KeyMetric> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return KeyMetric.defaultOrder
        val seen = LinkedHashSet<KeyMetric>()
        trimmed.split(",").forEach { token ->
            KeyMetric.fromRaw(token.trim())?.let { seen.add(it) }
        }
        return if (seen.isEmpty()) KeyMetric.defaultOrder else seen.toList()
    }
}
