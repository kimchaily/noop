package com.noop.ui

import android.content.Context
import com.noop.analytics.IntelligenceEngine

/**
 * A short history of what the scoring engine actually did, so "was anything recalculated, and when?"
 * is answerable inside the app.
 *
 * Until now the only record of a pass was a line in the strap log — which is off by default, is meant
 * for bug reports, and is gone as soon as it rotates. Meanwhile the pass became much quieter on purpose
 * (it now skips days that provably cannot have changed), so "nothing happened" and "nothing needed to
 * happen" look identical from the outside. This journal tells them apart.
 *
 * Deliberately small and lossy: the last [MAX_ENTRIES] passes, in its own preferences file, one line
 * each. It is a diagnostic aid, not data — losing it costs nothing, so it is never migrated, never
 * backed up, and never read by anything that computes a score.
 */
object AnalyzeJournal {
    private const val PREFS = "noop.analyzeJournal"
    private const val KEY_ENTRIES = "entries"

    /** Roughly a day of 15-minute backstop passes, which is as far back as this is useful. */
    const val MAX_ENTRIES = 30

    /** What caused a pass. Kept as a short stable token so an old entry still renders after an update. */
    enum class Trigger(val token: String, val label: String) {
        BACKSTOP("backstop", "Routine check"),
        OFFLOAD("offload", "After a strap sync"),
        EDIT("edit", "After a sleep correction"),
        IMPORT("import", "After an import"),
        MANUAL("manual", "You asked for it"),
        UPGRADE("upgrade", "After an app update"),
        ;

        companion object {
            fun from(token: String): Trigger = entries.firstOrNull { it.token == token } ?: BACKSTOP
        }
    }

    /** One recorded pass. [atSeconds] is wall-clock unix seconds at the moment the pass finished. */
    data class Entry(
        val atSeconds: Long,
        val trigger: Trigger,
        val windowDays: Int,
        val scored: Int,
        val skipped: Int,
        val fromDay: String?,
    ) {
        /** A plain sentence, in the user's terms — no counts-without-meaning. */
        val summary: String
            get() = when {
                scored == 0 -> "Nothing had changed, so nothing was recalculated."
                fromDay == null -> "Recalculated $scored ${dayWord(scored)}."
                skipped == 0 -> "Recalculated $scored ${dayWord(scored)}, from $fromDay onward."
                else -> "Recalculated $scored ${dayWord(scored)} from $fromDay onward; " +
                    "left $skipped unchanged ${dayWord(skipped)} alone."
            }

        private fun dayWord(n: Int) = if (n == 1) "day" else "days"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Newest first. Malformed lines are dropped rather than crashing a diagnostics screen. */
    fun read(context: Context): List<Entry> =
        prefs(context).getString(KEY_ENTRIES, "").orEmpty()
            .lineSequence()
            .mapNotNull(::decode)
            .sortedByDescending { it.atSeconds }
            .toList()

    /** Append a finished pass, trimming to [MAX_ENTRIES]. */
    fun record(
        context: Context,
        trigger: Trigger,
        report: IntelligenceEngine.PassReport,
        atSeconds: Long = System.currentTimeMillis() / 1000L,
    ) {
        val entry = Entry(
            atSeconds = atSeconds,
            trigger = trigger,
            windowDays = report.windowDays,
            scored = report.scored,
            skipped = report.skipped,
            fromDay = report.fromDay,
        )
        val kept = (listOf(entry) + read(context)).take(MAX_ENTRIES)
        prefs(context).edit()
            .putString(KEY_ENTRIES, kept.joinToString("\n", transform = ::encode))
            .apply()
    }

    /** Drop the whole journal. Diagnostics only — no score depends on it. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    /** The most recent pass that actually wrote something, or null when none of the kept ones did. */
    fun lastEffective(context: Context): Entry? = read(context).firstOrNull { it.scored > 0 }

    // ── Encoding ────────────────────────────────────────────────────────────────────────────────
    // Pipe-separated, because a day key is "YYYY-MM-DD" and a trigger token is [a-z]+ — neither can
    // contain a pipe or a newline, so no escaping is needed and a hand-read line stays hand-readable.

    internal fun encode(e: Entry): String =
        "${e.atSeconds}|${e.trigger.token}|${e.windowDays}|${e.scored}|${e.skipped}|${e.fromDay ?: ""}"

    internal fun decode(line: String): Entry? {
        val p = line.split("|")
        if (p.size < 6) return null
        return Entry(
            atSeconds = p[0].toLongOrNull() ?: return null,
            trigger = Trigger.from(p[1]),
            windowDays = p[2].toIntOrNull() ?: return null,
            scored = p[3].toIntOrNull() ?: return null,
            skipped = p[4].toIntOrNull() ?: return null,
            fromDay = p[5].ifBlank { null },
        )
    }
}
