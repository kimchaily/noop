package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the pure half of [WimHofJournal] — the morning/evening split, the increment, the day key,
 * and the first-run target suggestion.
 *
 * These decide what lands in the user's own journal, so the emphasis is on the ways it could be WRONG:
 * a session logged into the wrong half of the day, a second session overwriting the first, or a
 * suggestion confidently picking an item that has nothing to do with breathwork.
 */
class WimHofJournalTest {

    // ── Slot mapping ────────────────────────────────────────────────────────

    @Test fun hours_before_the_cutoff_are_morning() {
        assertEquals(WimHofSlot.MORNING, wimHofSlot(0))
        assertEquals(WimHofSlot.MORNING, wimHofSlot(6))
        assertEquals(WimHofSlot.MORNING, wimHofSlot(11))
    }

    @Test fun the_cutoff_hour_itself_is_already_evening() {
        assertEquals(WimHofSlot.EVENING, wimHofSlot(12))
        assertEquals(WimHofSlot.EVENING, wimHofSlot(18))
        assertEquals(WimHofSlot.EVENING, wimHofSlot(23))
    }

    @Test fun the_cutoff_is_configurable() {
        // An early riser who calls 10:00 the end of the morning.
        assertEquals(WimHofSlot.MORNING, wimHofSlot(9, cutoffHour = 10))
        assertEquals(WimHofSlot.EVENING, wimHofSlot(10, cutoffHour = 10))
    }

    @Test fun an_out_of_range_cutoff_is_clamped_rather_than_trusted() {
        // A stored 0 would make every session evening; clamped up to 1, midnight is still morning.
        assertEquals(WimHofSlot.MORNING, wimHofSlot(0, cutoffHour = 0))
        // A stored 99 would make every session morning; clamped down to 23, the last hour of the
        // day is still evening. Both assertions fail if the clamp is removed.
        assertEquals(WimHofSlot.EVENING, wimHofSlot(23, cutoffHour = 99))
        assertEquals(WimHofSlot.MORNING, wimHofSlot(22, cutoffHour = 99))
    }

    @Test fun every_hour_of_the_day_resolves_to_a_slot() {
        // There are exactly two journal items, so no hour may be left without a home — a 3 a.m.
        // session has to go somewhere.
        (0..23).forEach { h -> wimHofSlot(h) }
    }

    // ── The increment ───────────────────────────────────────────────────────

    @Test fun a_first_session_of_the_day_starts_from_zero() {
        assertEquals(3.0, incrementedWimHofValue(null, 3), 1e-9)
    }

    @Test fun a_second_session_adds_rather_than_overwrites() {
        // The behaviour the whole read-modify-write exists for: three rounds this morning and three
        // more later must read six, not three.
        assertEquals(6.0, incrementedWimHofValue(3.0, 3), 1e-9)
    }

    @Test fun zero_completed_rounds_leaves_the_value_alone() {
        assertEquals(4.0, incrementedWimHofValue(4.0, 0), 1e-9)
    }

    @Test fun a_negative_stored_value_is_floored_rather_than_subtracted_from() {
        // Only reachable by hand-editing the field; a real session must never count for less.
        assertEquals(2.0, incrementedWimHofValue(-5.0, 2), 1e-9)
    }

    @Test fun a_negative_round_count_cannot_reduce_the_day() {
        assertEquals(3.0, incrementedWimHofValue(3.0, -2), 1e-9)
    }

    // ── Day key ─────────────────────────────────────────────────────────────

    @Test fun a_session_lands_on_tomorrows_journal_row() {
        // Journal days are wake/cycle days: today's behaviour informs tomorrow morning's recovery.
        assertEquals("2026-08-22", wimHofJournalDayKey(LocalDate.of(2026, 8, 21)))
    }

    @Test fun the_day_key_rolls_over_month_and_year_boundaries() {
        assertEquals("2026-09-01", wimHofJournalDayKey(LocalDate.of(2026, 8, 31)))
        assertEquals("2027-01-01", wimHofJournalDayKey(LocalDate.of(2026, 12, 31)))
    }

    @Test fun the_day_key_matches_the_journal_cards_log_ahead_offset() {
        // Same helper the logging card uses, so an auto-logged row sits exactly where a hand-logged
        // "log ahead" row would.
        val today = LocalDate.of(2026, 8, 21)
        assertEquals(journalDayKey(-1L, today), wimHofJournalDayKey(today))
    }

    // ── Target suggestion ───────────────────────────────────────────────────

    private fun item(canonical: String, numeric: Boolean = false, hidden: Boolean = false) =
        JournalCatalogItem(
            canonical = canonical,
            kind = if (numeric) JournalKind.Numeric("rounds") else JournalKind.Bool,
            custom = true,
            hidden = hidden,
        )

    @Test fun picks_the_obvious_morning_and_evening_breathwork_items() {
        val suggested = suggestWimHofTargets(
            listOf(
                item("Breathwork morning", numeric = true),
                item("Breathwork evening", numeric = true),
                item("Did you drink any alcohol?"),
            ),
        )
        assertEquals("Breathwork morning", suggested.morningCanonical)
        assertEquals("Breathwork evening", suggested.eveningCanonical)
    }

    @Test fun recognises_german_wording_too() {
        val suggested = suggestWimHofTargets(
            listOf(item("Atemübung morgens"), item("Atemübung abends")),
        )
        assertEquals("Atemübung morgens", suggested.morningCanonical)
        assertEquals("Atemübung abends", suggested.eveningCanonical)
    }

    @Test fun a_time_of_day_item_that_is_not_breathwork_is_never_picked() {
        // "Morning pages" is a journalling habit, not breathwork. Guessing it would write rounds into
        // an unrelated item — worse than guessing nothing.
        val suggested = suggestWimHofTargets(
            listOf(item("Morning pages"), item("Evening walk"), item("Did you read before bed?")),
        )
        assertNull(suggested.morningCanonical)
        assertNull(suggested.eveningCanonical)
    }

    @Test fun a_breathwork_item_with_no_time_of_day_is_left_for_the_user() {
        // One ambiguous "Breathwork" item cannot serve both slots without double-counting the row.
        val suggested = suggestWimHofTargets(listOf(item("Breathwork", numeric = true)))
        assertNull(suggested.morningCanonical)
        assertNull(suggested.eveningCanonical)
    }

    @Test fun hidden_items_are_never_suggested() {
        val suggested = suggestWimHofTargets(
            listOf(item("Breathwork morning", hidden = true), item("Breathwork evening", hidden = true)),
        )
        assertNull(suggested.morningCanonical)
        assertNull(suggested.eveningCanonical)
    }

    @Test fun an_empty_catalog_suggests_nothing() {
        val suggested = suggestWimHofTargets(emptyList())
        assertNull(suggested.morningCanonical)
        assertNull(suggested.eveningCanonical)
    }

    @Test fun a_numeric_item_wins_over_an_equally_named_yes_no_one() {
        // A rounds counter is numeric by nature, so prefer the item that can actually show the count.
        val suggested = suggestWimHofTargets(
            listOf(
                item("Breathwork morning"),
                item("Morning breathwork rounds", numeric = true),
            ),
        )
        assertEquals("Morning breathwork rounds", suggested.morningCanonical)
    }

    // ── Targets accessor ────────────────────────────────────────────────────

    @Test fun targets_resolve_per_slot() {
        val t = WimHofJournalTargets("m-item", "e-item")
        assertEquals("m-item", t.canonicalFor(WimHofSlot.MORNING))
        assertEquals("e-item", t.canonicalFor(WimHofSlot.EVENING))
    }

    @Test fun an_unset_slot_resolves_to_null_so_the_caller_can_prompt() {
        val t = WimHofJournalTargets(null, "e-item")
        assertNull(t.canonicalFor(WimHofSlot.MORNING))
    }
}
