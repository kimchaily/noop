package com.noop.ui

import com.noop.data.JournalEntry
import com.noop.data.WhoopRepository
import java.time.LocalDate
import java.time.LocalTime

/*
 * WimHofJournal.kt — turn a finished Wim Hof session into ONE journal number.
 *
 * The contract, in the user's words: "automatically add the breathwork rounds into the journal (we have
 * two items: morning & evening) for morning or evening according to the day time. only full completed
 * rounds count and they increment the number for the (morning/evening) breathwork journal item."
 *
 * Three things follow from that and are worth stating, because each is a real decision:
 *
 *  1. INCREMENT, never overwrite. Two sessions in one evening must read 6, not 3. So the write is a
 *     read-modify-write against the row that is already there (imported or native), not a blind upsert.
 *  2. The engine decides what "completed" means ([WimHofState.completedRounds] — a round counts only
 *     once its recovery hold is over), so an abandoned round can never inflate the number here.
 *  3. The item is addressed by its CANONICAL key, never its display name. A rename in the journal
 *     catalog deliberately leaves the canonical key untouched (see JournalCatalog.kt), so a session
 *     logged today still lands on the same row after the user renames the item tomorrow.
 *
 * The pure half of this file (slot mapping, target suggestion, the increment itself) is JVM-testable
 * with no Room and no Android; only [logWimHofRounds] touches the database.
 */

/** Which of the user's two breathwork journal items a session belongs to. */
enum class WimHofSlot { MORNING, EVENING }

/** The stored canonical keys of the user's two items. Either may be null until they pick one. */
data class WimHofJournalTargets(
    val morningCanonical: String?,
    val eveningCanonical: String?,
) {
    /** The canonical key for [slot], or null when that slot hasn't been configured yet. */
    fun canonicalFor(slot: WimHofSlot): String? = when (slot) {
        WimHofSlot.MORNING -> morningCanonical
        WimHofSlot.EVENING -> eveningCanonical
    }
}

/**
 * Which slot an [hour] (0..23) belongs to: before [cutoffHour] is morning, from it on is evening.
 * A single cutoff rather than a morning/evening window pair, because every hour has to resolve to one
 * of exactly two items — a 3 a.m. session has to go somewhere, and "morning" is the honest answer.
 */
fun wimHofSlot(hour: Int, cutoffHour: Int = DEFAULT_MORNING_CUTOFF_HOUR): WimHofSlot {
    val safeCutoff = cutoffHour.coerceIn(MIN_CUTOFF_HOUR, MAX_CUTOFF_HOUR)
    return if (hour.coerceIn(0, 23) < safeCutoff) WimHofSlot.MORNING else WimHofSlot.EVENING
}

/** Convenience for callers holding a [LocalTime] rather than a bare hour. */
fun wimHofSlot(time: LocalTime, cutoffHour: Int = DEFAULT_MORNING_CUTOFF_HOUR): WimHofSlot =
    wimHofSlot(time.hour, cutoffHour)

/** Noon: the plainest reading of "morning" that needs no explaining. Configurable in Settings. */
const val DEFAULT_MORNING_CUTOFF_HOUR: Int = 12
const val MIN_CUTOFF_HOUR: Int = 1
const val MAX_CUTOFF_HOUR: Int = 23

/**
 * The journal day a session completed at [today] should be written to.
 *
 * Journal days in this app are WAKE/CYCLE days: an answer logged under day D describes the ~24 h that
 * fed into D's morning recovery (see [journalDayKey]'s note). Breathwork done today therefore belongs
 * to TOMORROW's key — `journalDayKey(-1L)`, the card's own "log ahead" offset — so the effects engine
 * lines the session up with the morning it could actually have influenced.
 */
fun wimHofJournalDayKey(today: LocalDate = LocalDate.now()): String =
    journalDayKey(daysBack = WIM_HOF_JOURNAL_DAYS_BACK, today = today)

/** Log-ahead offset: today's behaviour informs tomorrow's morning, so it lands on tomorrow's row. */
const val WIM_HOF_JOURNAL_DAYS_BACK: Long = -1L

/**
 * The value to write: what is already logged for the day plus the rounds just completed.
 *
 * Null (no row yet) starts from zero. A negative stored value — only reachable by hand-editing the
 * field — is floored at zero rather than subtracted from, so a typo can't make a real session count for
 * less than it was.
 */
fun incrementedWimHofValue(existing: Double?, completedRounds: Int): Double =
    (existing ?: 0.0).coerceAtLeast(0.0) + completedRounds.coerceAtLeast(0)

// ── First-run target suggestion ──────────────────────────────────────────────────────────────────

/**
 * Guess the user's morning/evening breathwork items from their catalog, for the Settings picker's
 * initial selection. A SUGGESTION only — it is presented in a picker the user can change, never
 * silently acted on, because writing rounds into the wrong journal item would be worse than writing
 * none at all.
 *
 * An item qualifies only if it names a time of day AND reads like a breathwork/practice item; a bare
 * "Morning pages" never gets picked up. Best-scoring item wins each slot, and one item can never fill
 * both (a single "breathwork" item is genuinely ambiguous, so it is left for the user to assign).
 */
fun suggestWimHofTargets(items: List<JournalCatalogItem>): WimHofJournalTargets {
    val scored = items
        .filterNot { it.hidden }
        .mapNotNull { item ->
            val haystack = "${item.displayName.orEmpty()} ${item.canonical}".lowercase()
            val slot = when {
                MORNING_TOKENS.any { it in haystack } -> WimHofSlot.MORNING
                EVENING_TOKENS.any { it in haystack } -> WimHofSlot.EVENING
                else -> null
            } ?: return@mapNotNull null
            val practiceHits = PRACTICE_TOKENS.count { it in haystack }
            if (practiceHits == 0) return@mapNotNull null
            // Prefer an item that already stores a number — a rounds counter is numeric by nature.
            val score = practiceHits * 2 + if (item.kind.isNumeric) 1 else 0
            Triple(slot, item.canonical, score)
        }

    fun bestFor(slot: WimHofSlot): String? = scored
        .filter { it.first == slot }
        .maxByOrNull { it.third }
        ?.second

    val morning = bestFor(WimHofSlot.MORNING)
    val evening = bestFor(WimHofSlot.EVENING)
    // Never let one item serve both slots — that would double-count a single row.
    return if (morning != null && morning == evening) {
        WimHofJournalTargets(null, null)
    } else {
        WimHofJournalTargets(morning, evening)
    }
}

// Deliberately no "am "/"pm ": they hide inside ordinary words ("exam", "team", "program") and would
// hand a confident wrong guess to the picker, which is worse than offering no guess at all.
private val MORNING_TOKENS = listOf("morning", "morgen", "früh", "fruh")
private val EVENING_TOKENS = listOf("evening", "abend", "night", "nacht")
private val PRACTICE_TOKENS = listOf(
    "breathwork", "breathing", "breath", "atem", "atmung", "wim hof", "wimhof", "whm",
    "round", "runde", "practice", "praxis",
)

// ── The write ────────────────────────────────────────────────────────────────────────────────────

/** What a logging attempt did, so the summary card can tell the truth about it. */
sealed class WimHofLogResult {
    /** Wrote [total] rounds to [canonical] on [dayKey] (of which [added] came from this session). */
    data class Logged(val canonical: String, val dayKey: String, val added: Int, val total: Double) : WimHofLogResult()

    /** Nothing to log — no round was carried through to completion. */
    object NoCompletedRounds : WimHofLogResult()

    /** No journal item is configured for this slot yet; the UI points at Settings. */
    data class NoTarget(val slot: WimHofSlot) : WimHofLogResult()

    /** Auto-logging is switched off in Settings. */
    object Disabled : WimHofLogResult()
}

/**
 * Add [completedRounds] to the [slot]'s journal item for [dayKey], creating the row if needed.
 *
 * Reads the day with the existing repository accessor (from == to == the single day) rather than adding
 * a DAO method, then writes back the incremented value. The row is written with `answeredYes = true`
 * alongside the number — the convention every numeric journal write in this app follows (see the
 * Insights logging card) so the effects engine still counts the day as logged and its with/without
 * split keeps working.
 *
 * Reads BOTH sources when totalling: the user may have an imported WHOOP row for the same day, and the
 * native row is what wins in the merge, so the increment has to start from whichever value is actually
 * on screen.
 */
suspend fun logWimHofRounds(
    repo: WhoopRepository,
    targets: WimHofJournalTargets,
    slot: WimHofSlot,
    dayKey: String,
    completedRounds: Int,
    enabled: Boolean = true,
): WimHofLogResult {
    if (!enabled) return WimHofLogResult.Disabled
    if (completedRounds <= 0) return WimHofLogResult.NoCompletedRounds
    val canonical = targets.canonicalFor(slot) ?: return WimHofLogResult.NoTarget(slot)

    val key = normJournalKey(canonical)
    val imported = repo.journal("my-whoop", dayKey, dayKey)
    val native = repo.journal(JOURNAL_DEVICE_ID, dayKey, dayKey)
    // Native wins on a collision, matching mergeJournalEntries — the in-app value is the one the user
    // sees and edits, so it is the one we must add to.
    val existing = (native + imported)
        .firstOrNull { normJournalKey(it.question) == key }
        ?.numericValue

    val total = incrementedWimHofValue(existing, completedRounds)
    repo.upsertJournal(
        listOf(
            JournalEntry(
                deviceId = JOURNAL_DEVICE_ID,
                day = dayKey,
                question = canonical,
                answeredYes = true,
                numericValue = total,
            ),
        ),
    )
    return WimHofLogResult.Logged(canonical = canonical, dayKey = dayKey, added = completedRounds, total = total)
}
