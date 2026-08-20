package com.noop.ui

import java.util.prefs.Preferences

// MARK: - NoopPrefs — the JVM (Compose Desktop) preference store
//
// Android twin lives in MainActivity.kt and wraps SharedPreferences. On the JVM there is no
// SharedPreferences, so this re-implements the SAME key/value contract on top of
// `java.util.prefs.Preferences` (the JDK's cross-platform per-user backing store). The public
// surface mirrors the Android `NoopPrefs.of(context)` call chain — callers do
// `NoopPrefs.getString(...)` and `NoopPrefs.edit().putString(...).apply()` exactly as on Android,
// minus the `Context` argument (the JVM node is process-wide + user-scoped).
//
// Every key constant from the Android twin is preserved byte-for-byte so the persisted state
// stays cross-platform compatible at the string level. Only keys actually referenced by the
// ported UI are declared here; add more as the port grows.

object NoopPrefs {
    const val NAME = "noop_prefs"

    const val KEY_ONBOARDED = "noop.onboarded"
    const val KEY_LAST_SEEN_CHANGELOG = "noop.lastSeenChangelogVersion"
    const val KEY_ACCEPTED_TERMS_VERSION = "noop.acceptedTermsVersion"
    const val KEY_ACCEPTED_TERMS_AT = "noop.acceptedTermsAt"

    const val KEY_BACKGROUND_CONNECTION = "noop.backgroundConnection"
    const val KEY_CONTINUOUS_HRV = "noop.continuousHrv"
    const val KEY_CONTINUOUS_HRV_OVERNIGHT = "noop.continuousHrvOvernight"

    const val KEY_UNIT_SYSTEM = "units.system"
    const val KEY_TEMPERATURE_UNIT = "units.temperature"

    const val KEY_ANALYZE_WATERMARK = "noop.analyzeWatermark"

    const val KEY_HC_AUTO_SYNC = "noop.hcAutoSync"
    const val KEY_HC_SYNC_HOURS = "noop.hcSyncHours"
    const val KEY_HC_LAST_SYNC = "noop.hcLastSync"

    /** The backing Preferences node. User-scoped, process-wide. */
    private val node: Preferences = Preferences.userRoot().node("com/noop/$NAME")

    /** SharedPreferences-style accessor — kept for source parity with the Android twin. */
    fun of(): NoopPrefs = this

    fun getString(key: String, default: String?): String? = node.get(key, default)
    fun getBoolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)
    fun getInt(key: String, default: Int): Int = node.getInt(key, default)
    fun getLong(key: String, default: Long): Long = node.getLong(key, default)
    fun getFloat(key: String, default: Float): Float = node.getFloat(key, default)

    fun setUnitSystem(system: UnitSystem) {
        edit().putString(KEY_UNIT_SYSTEM, system.raw).apply()
    }

    fun setTemperatureUnit(unit: TemperatureUnit?) {
        edit().apply {
            if (unit == null) remove(KEY_TEMPERATURE_UNIT) else putString(KEY_TEMPERATURE_UNIT, unit.raw)
        }.apply()
    }

    fun analyzeWatermark(): String? = getString(KEY_ANALYZE_WATERMARK, null)

    /** Begin a mutating transaction. Mirrors `SharedPreferences.edit()`; call `.apply()` to flush. */
    fun edit(): Editor = Editor

    /** SharedPreferences.Editor-style mutator. Every setter returns `this` so the calls chain. */
    object Editor {
        private var dirty = false

        fun putString(key: String, value: String?): Editor = apply {
            if (value == null) node.remove(key) else node.put(key, value)
            dirty = true
        }

        fun putBoolean(key: String, value: Boolean): Editor = apply {
            node.putBoolean(key, value); dirty = true
        }

        fun putInt(key: String, value: Int): Editor = apply {
            node.putInt(key, value); dirty = true
        }

        fun putLong(key: String, value: Long): Editor = apply {
            node.putLong(key, value); dirty = true
        }

        fun putFloat(key: String, value: Float): Editor = apply {
            node.putFloat(key, value); dirty = true
        }

        fun remove(key: String): Editor = apply {
            node.remove(key); dirty = true
        }

        fun clear(): Editor = apply {
            runCatching { node.clear() }; dirty = true
        }

        /** Flush pending writes to the backing store (matches `SharedPreferences.Editor.apply()`). */
        fun apply() {
            if (dirty) runCatching { node.flush() }
            dirty = false
        }
    }
}
