package com.noop.ui

import android.content.Context

/**
 * Per-day input digests for the scoring pass: "what did this day's raw data look like the last time we
 * scored it?".
 *
 * A day whose digest is unchanged cannot produce a different score, so the pass skips re-deriving it and
 * every day before it. That is what stopped Choop re-reading three weeks of 1 Hz data every fifteen
 * minutes.
 *
 * ## Why this is its own object rather than a ViewModel field
 *
 * It started as a private field on [AppViewModel], which meant only the 15-minute backstop could consult
 * it — the post-sync pass in the BLE client had no way to reach it and so re-derived the full window
 * every single time. In practice the sync path is the one that runs most (the backstop lives on the
 * ViewModel and stops when Android freezes the app in the background, while the sync runs under the
 * foreground service), so the optimisation was missing from the path that mattered most. Anything that
 * runs a scoring pass has to be able to read and write these, so they live where both can reach them.
 *
 * ## Why losing it is safe
 *
 * A missing digest reads as "this day changed", so the worst case is one unnecessary pass — never a
 * stale score. That asymmetry is deliberate and is why this is a plain preferences file with no
 * migration, no backup and no integrity check: it is a cache of work already done, not data.
 *
 * Kept OUT of the main preferences file because the entries are per-day and accumulate (~365 a year);
 * mixing them in would bloat a file the app reads on every launch.
 */
object DayDigestStore {
    private const val PREFS = "noop.dayDigests"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The digest stored when [day] was last scored, or null when it never was (⇒ treat as changed). */
    fun get(context: Context, day: String): String? = prefs(context).getString(day, null)

    /**
     * Record [digest] as what [day] was scored against.
     *
     * Callers must only do this AFTER the day's rows are actually written. Storing it earlier would let
     * an interrupted pass claim a day as up to date whose score never landed, and the next pass would
     * skip it — a stale score with nothing left to trigger its repair.
     */
    fun put(context: Context, day: String, digest: String) {
        prefs(context).edit().putString(day, digest).apply()
    }

    /**
     * Forget every day, so the next pass treats the whole window as changed.
     *
     * For when raw rows arrive by a path the digest cannot see coming — a merge import, which can add
     * measurements to days that are weeks old. Clearing is always safe (one wasted pass); failing to
     * clear is not (a stale score), so this errs deliberately in the cheap direction.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
