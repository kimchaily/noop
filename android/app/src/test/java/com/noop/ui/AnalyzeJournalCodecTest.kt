package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pass journal's line format and its plain-language summaries.
 *
 * Worth pinning for two reasons. The format is what an OLD entry is stored in — a journal written by
 * an earlier build must still render after an update, or the diagnostics view would lie about history
 * exactly when a user is trying to understand it. And the summaries are the whole point of the view:
 * "nothing had changed" and "recalculated 1 day" are the sentences that tell a quiet pass apart from
 * no pass at all.
 *
 * The SharedPreferences read/write around this needs a device and is not covered here.
 */
class AnalyzeJournalCodecTest {

    private fun entry(
        at: Long = 1_754_000_000L,
        trigger: AnalyzeJournal.Trigger = AnalyzeJournal.Trigger.BACKSTOP,
        window: Int = 21,
        scored: Int = 1,
        skipped: Int = 20,
        from: String? = "2026-08-10",
    ) = AnalyzeJournal.Entry(at, trigger, window, scored, skipped, from)

    @Test fun aLineSurvivesTheRoundTrip() {
        val e = entry()
        assertEquals(e, AnalyzeJournal.decode(AnalyzeJournal.encode(e)))
    }

    @Test fun anAbsentFromDayStaysAbsent() {
        // "nothing changed" writes no from-day; it must not come back as an empty string, which would
        // render as a day key of "".
        val e = entry(scored = 0, skipped = 21, from = null)
        val back = AnalyzeJournal.decode(AnalyzeJournal.encode(e))
        assertNull(back?.fromDay)
        assertEquals(e, back)
    }

    @Test fun everyTriggerRoundTripsByToken() {
        for (t in AnalyzeJournal.Trigger.entries) {
            val back = AnalyzeJournal.decode(AnalyzeJournal.encode(entry(trigger = t)))
            assertEquals(t, back?.trigger)
        }
    }

    @Test fun anUnknownTriggerTokenDegradesInsteadOfVanishing() {
        // A journal written by a newer build can name a trigger this one has never heard of. Losing
        // the whole entry would hide a pass that really happened; falling back keeps the counts.
        val back = AnalyzeJournal.decode("1754000000|somethingNew|21|3|18|2026-08-01")
        assertEquals(AnalyzeJournal.Trigger.BACKSTOP, back?.trigger)
        assertEquals(3, back?.scored)
    }

    @Test fun malformedLinesAreDroppedNotCrashed() {
        // A diagnostics screen must never be the thing that crashes.
        for (bad in listOf("", "garbage", "1|2|3", "notANumber|backstop|21|1|20|2026-08-10")) {
            assertNull(bad, AnalyzeJournal.decode(bad))
        }
    }

    @Test fun aLineNeverContainsItsOwnSeparators() {
        // The format has no escaping, which is only safe while no field can hold a pipe or newline.
        val line = AnalyzeJournal.encode(entry())
        assertEquals(6, line.split("|").size)
        assertTrue(line, !line.contains("\n"))
    }

    // ── The sentences the user actually reads ───────────────────────────────────────────────────

    @Test fun aQuietPassSaysNothingChangedRatherThanZero() {
        assertEquals(
            "Nothing had changed, so nothing was recalculated.",
            entry(scored = 0, skipped = 21, from = null).summary,
        )
    }

    @Test fun aSkippingPassNamesBothHalves() {
        val s = entry(scored = 1, skipped = 20, from = "2026-08-10").summary
        assertTrue(s, s.contains("Recalculated 1 day"))
        assertTrue(s, s.contains("2026-08-10"))
        assertTrue(s, s.contains("20 unchanged days"))
    }

    @Test fun aFullPassDoesNotClaimToHaveSkippedAnything() {
        val s = entry(scored = 21, skipped = 0, from = "2026-07-21").summary
        assertTrue(s, s.contains("Recalculated 21 days"))
        assertTrue(s, !s.contains("unchanged"))
    }

    @Test fun singularAndPluralDaysReadCorrectly() {
        assertTrue(entry(scored = 1, skipped = 0).summary.contains("1 day,"))
        assertTrue(entry(scored = 2, skipped = 0).summary.contains("2 days,"))
        assertTrue(entry(scored = 3, skipped = 1).summary.contains("1 unchanged day"))
    }
}
