package com.noop.ui

import com.noop.analytics.Zones
import java.util.prefs.Preferences

/**
 * Desktop port of the Android `ProfileStore` (which lives in `SettingsScreen.kt` on
 * Android and uses `SharedPreferences`). This version uses `java.util.prefs.Preferences`
 * — the JDK's cross-platform per-user backing store — matching the approach taken by
 * [NoopPrefs].
 *
 * Holds the user's body-profile data (age, sex, weight, height, etc.) that drives
 * HR-zone calculations, calorie estimates, and recovery baselines. Without these,
 * the analytics engine falls back to population-average defaults (age 30, 75 kg, etc.)
 * which are less accurate for the individual user.
 *
 * The backup snapshot/apply methods mirror the Android twin so profile data can be
 * included in `.noopbak` exports (via the `settings.json` entry).
 */
class ProfileStore private constructor(private val prefs: Preferences) {

    companion object {
        private const val NODE = "noop_profile"

        private const val KEY_AGE = "age"
        private const val KEY_SEX = "sex"
        private const val KEY_WEIGHT_KG = "weight_kg"
        private const val KEY_HEIGHT_CM = "height_cm"
        private const val KEY_WAIST_CM = "waist_cm"
        private const val KEY_HR_MAX_OVERRIDE = "hr_max_override"

        /** Defaults match the Android twin exactly. */
        const val DEFAULT_AGE = 30
        const val DEFAULT_SEX = "male"
        const val DEFAULT_WEIGHT_KG = 75.0
        const val DEFAULT_HEIGHT_CM = 178.0

        fun create(): ProfileStore =
            ProfileStore(Preferences.userRoot().node("com/noop/$NODE"))
    }

    var age: Int
        get() = prefs.getInt(KEY_AGE, DEFAULT_AGE)
        set(value) { prefs.putInt(KEY_AGE, value.coerceIn(13, 100)); prefs.flush() }

    var sex: String
        get() = prefs.get(KEY_SEX, DEFAULT_SEX)
        set(value) {
            val v = when (value.lowercase()) { "male", "female", "nonbinary" -> value.lowercase(); else -> DEFAULT_SEX }
            prefs.put(KEY_SEX, v); prefs.flush()
        }

    var weightKg: Double
        get() = prefs.getDouble(KEY_WEIGHT_KG, DEFAULT_WEIGHT_KG)
        set(value) { prefs.putDouble(KEY_WEIGHT_KG, value.coerceIn(30.0, 250.0)); prefs.flush() }

    var heightCm: Double
        get() = prefs.getDouble(KEY_HEIGHT_CM, DEFAULT_HEIGHT_CM)
        set(value) { prefs.putDouble(KEY_HEIGHT_CM, value.coerceIn(120.0, 230.0)); prefs.flush() }

    var waistCm: Double
        get() = prefs.getDouble(KEY_WAIST_CM, 0.0)
        set(value) { prefs.putDouble(KEY_WAIST_CM, value.coerceIn(0.0, 200.0)); prefs.flush() }

    var hrMaxOverride: Int
        get() = prefs.getInt(KEY_HR_MAX_OVERRIDE, 0)
        set(value) { prefs.putInt(KEY_HR_MAX_OVERRIDE, value.coerceIn(0, 230)); prefs.flush() }

    /** Tanaka-formula auto HR-max from [age]. */
    val hrMaxAuto: Int get() = Zones.hrMaxTanaka(age)

    /** Effective HR-max: the override if set (>0), else the Tanaka auto value. */
    val hrMax: Int get() = if (hrMaxOverride > 0) hrMaxOverride else hrMaxAuto

    // -- Backup integration (mirrors Android ProfileStore.backupSnapshot / applyBackup) --

    /**
     * Return only the fields the user has explicitly set (checked via `prefs.keys()`).
     * This prevents restoring defaults onto another device and overwriting its real values.
     */
    fun backupSnapshot(): Map<String, Any> {
        val keys = prefs.keys()
        val map = mutableMapOf<String, Any>()
        if (KEY_AGE in keys) map["profile.age"] = age
        if (KEY_SEX in keys) map["profile.sex"] = sex
        if (KEY_WEIGHT_KG in keys) map["profile.weightKg"] = weightKg
        if (KEY_HEIGHT_CM in keys) map["profile.heightCm"] = heightCm
        if (KEY_WAIST_CM in keys) map["profile.waistCm"] = waistCm
        if (KEY_HR_MAX_OVERRIDE in keys) map["profile.hrMax"] = hrMaxOverride
        return map
    }

    /** Apply backed-up values. Missing keys are left untouched (no overwrite). */
    fun applyBackup(values: Map<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        fun <T> get(key: String): T? = values[key] as? T
        get<Int>("profile.age")?.let { age = it }
        get<String>("profile.sex")?.let { sex = it }
        get<Double>("profile.weightKg")?.let { weightKg = it }
        get<Double>("profile.heightCm")?.let { heightCm = it }
        get<Double>("profile.waistCm")?.let { waistCm = it }
        get<Int>("profile.hrMax")?.let { hrMaxOverride = it }
    }
}
