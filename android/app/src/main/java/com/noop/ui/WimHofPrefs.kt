package com.noop.ui

import android.content.Context
import com.noop.analytics.RetentionMode
import com.noop.analytics.WimHofConfig
import org.json.JSONArray
import org.json.JSONObject

/*
 * WimHofPrefs.kt — on-device settings + a short session history for the Wim Hof screen.
 *
 * Follows the two patterns this app already uses rather than inventing a third: an object of typed
 * getters/setters over [NoopPrefs] (as BiofeedbackPrefs does) for the settings, and a JSON array in one
 * pref key, pruned on load (as CaffeineLog does) for the history. Nothing here leaves the device.
 *
 * KNOWN LIMIT, surfaced in the UI rather than hidden: a `.noopbak` backup carries the SQLite database,
 * not SharedPreferences — so these settings and this history do NOT survive an import, while the
 * JOURNAL rows the sessions write DO. That is the right way round: the durable record of "I did three
 * rounds this morning" is the journal entry, and the history here is a local convenience.
 */

/** How a session is guided aloud. Cycles in this order on the screen's audio chip. */
enum class WimHofAudioMode(val label: String) {
    /** Soft synthesised tones at each phase — the quietest useful guidance. */
    Tones("Tones"),

    /** Spoken cues through the phone's own text-to-speech engine, plus the tones. */
    Spoken("Spoken"),

    /** Silent. The animation and (if worn) the strap's transition buzzes still guide. */
    Off("Off");

    /** The next mode in the cycle, for the single-tap chip. */
    fun next(): WimHofAudioMode = entries[(ordinal + 1) % entries.size]
}

/** One finished session, kept for the "best hold" line and the history card. */
data class WimHofSessionRecord(
    val startedAtEpochSec: Long,
    val rounds: Int,
    val breathsPerRound: Int,
    /** Achieved hold per completed-or-attempted round, oldest first. */
    val retentionSeconds: List<Int>,
    /** Rounds carried through their recovery hold — what was written to the journal. */
    val completedRounds: Int,
    /** The journal item the rounds went to, or null when none was configured / logging was off. */
    val journaledTo: String? = null,
) {
    val bestRetentionSeconds: Int? get() = retentionSeconds.maxOrNull()
}

object WimHofPrefs {

    // ── Keys ────────────────────────────────────────────────────────────────
    // "wimhof."-prefixed inside the shared noop_prefs file, matching how BiofeedbackPrefs namespaces
    // its own group. Android-only feature, so no Swift key parity to keep.

    private const val KEY_ROUNDS = "wimhof.rounds"
    private const val KEY_BREATHS = "wimhof.breathsPerRound"
    private const val KEY_CYCLE_MS = "wimhof.breathCycleMs"
    private const val KEY_PREPARE_SEC = "wimhof.prepareSeconds"
    private const val KEY_RECOVERY_SEC = "wimhof.recoveryHoldSeconds"
    private const val KEY_RETENTION_FIXED = "wimhof.retentionFixed"
    private const val KEY_RETENTION_TARGETS = "wimhof.retentionTargets"
    private const val KEY_AUDIO_MODE = "wimhof.audioMode"
    private const val KEY_SHOW_BPM = "wimhof.showBpm"
    private const val KEY_TRACK_URI = "wimhof.trackUri"
    private const val KEY_TRACK_VOLUME = "wimhof.trackVolume"
    private const val KEY_AUTO_JOURNAL = "wimhof.autoJournal"
    private const val KEY_MORNING_ITEM = "wimhof.journalMorning"
    private const val KEY_EVENING_ITEM = "wimhof.journalEvening"
    private const val KEY_CUTOFF_HOUR = "wimhof.morningCutoffHour"
    private const val KEY_SAFETY_VERSION = "wimhof.safetyAcceptedVersion"
    private const val KEY_HISTORY = "wimhof.history"

    /** Keep the history short — it backs a "best hold" line and a few rows, not an archive. */
    const val HISTORY_LIMIT = 30

    // ── Session config ──────────────────────────────────────────────────────

    /** The user's saved session settings, already sanitised into supported ranges. */
    fun config(context: Context): WimHofConfig {
        val p = NoopPrefs.of(context)
        val fixed = p.getBoolean(KEY_RETENTION_FIXED, false)
        return WimHofConfig(
            rounds = p.getInt(KEY_ROUNDS, WimHofConfig.DEFAULT_ROUNDS),
            breathsPerRound = p.getInt(KEY_BREATHS, WimHofConfig.DEFAULT_BREATHS_PER_ROUND),
            breathCycleMs = p.getInt(KEY_CYCLE_MS, WimHofConfig.DEFAULT_BREATH_CYCLE_MS),
            prepareSeconds = p.getInt(KEY_PREPARE_SEC, WimHofConfig.DEFAULT_PREPARE_SECONDS),
            recoveryHoldSeconds = p.getInt(KEY_RECOVERY_SEC, WimHofConfig.DEFAULT_RECOVERY_HOLD_SECONDS),
            retention = if (fixed) {
                RetentionMode.Fixed(decodeTargets(p.getString(KEY_RETENTION_TARGETS, null)))
            } else {
                RetentionMode.TapToEnd()
            },
        ).sanitised()
    }

    fun saveConfig(context: Context, config: WimHofConfig) {
        val safe = config.sanitised()
        val e = NoopPrefs.of(context).edit()
            .putInt(KEY_ROUNDS, safe.rounds)
            .putInt(KEY_BREATHS, safe.breathsPerRound)
            .putInt(KEY_CYCLE_MS, safe.breathCycleMs)
            .putInt(KEY_PREPARE_SEC, safe.prepareSeconds)
            .putInt(KEY_RECOVERY_SEC, safe.recoveryHoldSeconds)
        when (val r = safe.retention) {
            is RetentionMode.TapToEnd -> e.putBoolean(KEY_RETENTION_FIXED, false)
            is RetentionMode.Fixed -> e
                .putBoolean(KEY_RETENTION_FIXED, true)
                .putString(KEY_RETENTION_TARGETS, encodeTargets(r.targetsSeconds))
        }
        e.apply()
    }

    /** Comma-joined seconds, so the stored value stays readable in a prefs dump. */
    internal fun encodeTargets(targets: List<Int>): String = targets.joinToString(",")

    internal fun decodeTargets(raw: String?): List<Int> =
        raw?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: RetentionMode.DEFAULT_FIXED_TARGETS

    // ── Presentation ────────────────────────────────────────────────────────

    fun audioMode(context: Context): WimHofAudioMode {
        val name = NoopPrefs.of(context).getString(KEY_AUDIO_MODE, null)
        return WimHofAudioMode.entries.firstOrNull { it.name == name } ?: WimHofAudioMode.Tones
    }

    fun setAudioMode(context: Context, mode: WimHofAudioMode) =
        NoopPrefs.of(context).edit().putString(KEY_AUDIO_MODE, mode.name).apply()

    /**
     * Whether to show live heart rate over the breath animation. Default OFF, deliberately: power
     * breathing RAISES heart rate and suppresses HRV by design, so the number is easy to misread as
     * something going wrong. The user asked for it to be switchable, and this is the honest default.
     */
    fun showBpm(context: Context): Boolean = NoopPrefs.of(context).getBoolean(KEY_SHOW_BPM, false)

    fun setShowBpm(context: Context, on: Boolean) =
        NoopPrefs.of(context).edit().putBoolean(KEY_SHOW_BPM, on).apply()

    // ── Optional background track (LINKED, never copied — see WimHofAudio) ───

    fun trackUri(context: Context): String? =
        NoopPrefs.of(context).getString(KEY_TRACK_URI, null)?.takeIf { it.isNotBlank() }

    fun setTrackUri(context: Context, uri: String?) =
        NoopPrefs.of(context).edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_TRACK_URI) else putString(KEY_TRACK_URI, uri)
        }.apply()

    fun trackVolume(context: Context): Float =
        NoopPrefs.of(context).getFloat(KEY_TRACK_VOLUME, DEFAULT_TRACK_VOLUME).coerceIn(0f, 1f)

    fun setTrackVolume(context: Context, volume: Float) =
        NoopPrefs.of(context).edit().putFloat(KEY_TRACK_VOLUME, volume.coerceIn(0f, 1f)).apply()

    /** Quiet by default — it plays UNDER the cues, which have to stay the audible timing signal. */
    const val DEFAULT_TRACK_VOLUME = 0.35f

    // ── Journal wiring ──────────────────────────────────────────────────────

    fun autoJournalEnabled(context: Context): Boolean =
        NoopPrefs.of(context).getBoolean(KEY_AUTO_JOURNAL, true)

    fun setAutoJournalEnabled(context: Context, on: Boolean) =
        NoopPrefs.of(context).edit().putBoolean(KEY_AUTO_JOURNAL, on).apply()

    /** The chosen morning/evening items as CANONICAL keys (never display names — a rename must not
     *  break the wiring, and the canonical key is exactly what survives one). */
    fun targets(context: Context): WimHofJournalTargets {
        val p = NoopPrefs.of(context)
        return WimHofJournalTargets(
            morningCanonical = p.getString(KEY_MORNING_ITEM, null)?.takeIf { it.isNotBlank() },
            eveningCanonical = p.getString(KEY_EVENING_ITEM, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun setTarget(context: Context, slot: WimHofSlot, canonical: String?) {
        val key = if (slot == WimHofSlot.MORNING) KEY_MORNING_ITEM else KEY_EVENING_ITEM
        NoopPrefs.of(context).edit().apply {
            if (canonical.isNullOrBlank()) remove(key) else putString(key, canonical)
        }.apply()
    }

    fun morningCutoffHour(context: Context): Int =
        NoopPrefs.of(context).getInt(KEY_CUTOFF_HOUR, DEFAULT_MORNING_CUTOFF_HOUR)
            .coerceIn(MIN_CUTOFF_HOUR, MAX_CUTOFF_HOUR)

    fun setMorningCutoffHour(context: Context, hour: Int) =
        NoopPrefs.of(context).edit()
            .putInt(KEY_CUTOFF_HOUR, hour.coerceIn(MIN_CUTOFF_HOUR, MAX_CUTOFF_HOUR)).apply()

    // ── Safety acknowledgement ──────────────────────────────────────────────

    /** Bump when the safety copy changes materially, so the gate is shown again. */
    const val SAFETY_VERSION = "1"

    fun safetyAccepted(context: Context): Boolean =
        NoopPrefs.of(context).getString(KEY_SAFETY_VERSION, null) == SAFETY_VERSION

    fun acceptSafety(context: Context) =
        NoopPrefs.of(context).edit().putString(KEY_SAFETY_VERSION, SAFETY_VERSION).apply()

    /** Clear the acknowledgement so the gate shows again (the "Review safety information" row). */
    fun clearSafetyAcceptance(context: Context) =
        NoopPrefs.of(context).edit().remove(KEY_SAFETY_VERSION).apply()

    // ── Session history ─────────────────────────────────────────────────────

    fun history(context: Context): List<WimHofSessionRecord> =
        decodeHistory(NoopPrefs.of(context).getString(KEY_HISTORY, "") ?: "")

    /** Prepend [record] (newest first) and keep at most [HISTORY_LIMIT] entries. */
    fun appendSession(context: Context, record: WimHofSessionRecord) {
        val next = (listOf(record) + history(context)).take(HISTORY_LIMIT)
        NoopPrefs.of(context).edit().putString(KEY_HISTORY, encodeHistory(next)).apply()
    }

    fun clearHistory(context: Context) =
        NoopPrefs.of(context).edit().remove(KEY_HISTORY).apply()

    /** Best hold ever recorded on this device, or null before the first finished round. */
    fun bestRetentionSeconds(context: Context): Int? =
        history(context).mapNotNull { it.bestRetentionSeconds }.maxOrNull()

    // ── History codec (pure — unit-tested without Android) ───────────────────

    internal fun encodeHistory(records: List<WimHofSessionRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject().apply {
                    put("startedAt", r.startedAtEpochSec)
                    put("rounds", r.rounds)
                    put("breaths", r.breathsPerRound)
                    put("completed", r.completedRounds)
                    put("holds", JSONArray().also { a -> r.retentionSeconds.forEach(a::put) })
                    r.journaledTo?.let { put("journaledTo", it) }
                },
            )
        }
        return arr.toString()
    }

    internal fun decodeHistory(json: String): List<WimHofSessionRecord> {
        if (json.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<WimHofSessionRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val holdsArr = o.optJSONArray("holds")
            val holds = if (holdsArr == null) emptyList() else {
                (0 until holdsArr.length()).map { holdsArr.optInt(it, 0) }
            }
            out.add(
                WimHofSessionRecord(
                    startedAtEpochSec = o.optLong("startedAt", 0L),
                    rounds = o.optInt("rounds", 0),
                    breathsPerRound = o.optInt("breaths", 0),
                    retentionSeconds = holds,
                    completedRounds = o.optInt("completed", 0),
                    journaledTo = if (o.has("journaledTo")) o.optString("journaledTo") else null,
                ),
            )
        }
        return out.take(HISTORY_LIMIT)
    }
}
