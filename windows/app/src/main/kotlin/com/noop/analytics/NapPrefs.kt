package com.noop.analytics

import java.util.prefs.Preferences

/**
 * NapPrefs — desktop (JVM) variant of the on-device short-nap detection pref surface.
 *
 * Mirrors the Android twin value-for-value. Uses java.util.prefs.Preferences instead of
 * SharedPreferences, but the key strings MATCH so the platforms read consistent prefs.
 *
 * Kept tiny and dependency-free so the BLE-layer hook can read [config] without pulling in
 * the UI layer. Defaults OFF (opt-in, manual-first).
 */
object NapPrefs {

    private const val KEY_ENABLED = "noop.napDetectionEnabled"
    private const val KEY_HIGH_WATER = "noop.napHighWaterTs"

    private val prefs: Preferences = Preferences.userNodeForPackage(NapPrefs::class.java)

    /** Feature toggle (default OFF — opt-in, manual-first). */
    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(on: Boolean) {
        prefs.putBoolean(KEY_ENABLED, on)
        prefs.flush()
    }

    /**
     * High-water mark (unix seconds): a nap whose window ENDS at/before this was already past when nap
     * detection first ran, so it must NOT be surfaced. Seeded to "now" on the FIRST read; advanced as
     * windows are judged. 0 = never seeded.
     */
    fun highWaterTs(): Long = prefs.getLong(KEY_HIGH_WATER, 0L)

    fun setHighWaterTs(ts: Long) {
        prefs.putLong(KEY_HIGH_WATER, ts)
        prefs.flush()
    }

    /**
     * Return the effective high-water mark, seeding it to [nowSec] the first time it's read (so the very
     * first offload after enabling can't surface historical naps). Idempotent once seeded.
     */
    fun highWaterOrSeed(nowSec: Long): Long {
        val existing = highWaterTs()
        if (existing > 0L) return existing
        setHighWaterTs(nowSec)
        return nowSec
    }

    /** Build the engine config from the persisted toggle. Thresholds use the engine defaults. */
    fun config(): NapConfig = NapConfig(enabled = enabled())
}
