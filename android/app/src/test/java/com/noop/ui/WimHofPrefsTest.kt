package com.noop.ui

import com.noop.analytics.RetentionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure codecs in [WimHofPrefs] — the session-history JSON blob and the fixed-retention
 * ladder. Both are read back on every launch, so a decode that throws (or quietly drops a field) would
 * take out the screen or lose the user's personal best; every test here is about surviving bad input.
 *
 * Plain JVM: `org.json` is on the unit-test classpath (android.jar's stubs throw), so the real codec
 * runs here exactly as it does on device.
 */
class WimHofPrefsTest {

    private fun record(
        started: Long = 1_770_000_000L,
        rounds: Int = 3,
        breaths: Int = 30,
        holds: List<Int> = listOf(62, 84, 91),
        completed: Int = 3,
        journaledTo: String? = "Breathwork morning",
    ) = WimHofSessionRecord(
        startedAtEpochSec = started,
        rounds = rounds,
        breathsPerRound = breaths,
        retentionSeconds = holds,
        completedRounds = completed,
        journaledTo = journaledTo,
    )

    // ── History round trip ──────────────────────────────────────────────────

    @Test fun a_record_survives_the_round_trip_field_for_field() {
        val original = record()
        val decoded = WimHofPrefs.decodeHistory(WimHofPrefs.encodeHistory(listOf(original)))
        assertEquals(listOf(original), decoded)
    }

    @Test fun several_records_keep_their_order() {
        val records = listOf(record(started = 3), record(started = 2), record(started = 1))
        assertEquals(records, WimHofPrefs.decodeHistory(WimHofPrefs.encodeHistory(records)))
    }

    @Test fun a_record_with_no_journal_target_round_trips_as_null() {
        val original = record(journaledTo = null, completed = 0, holds = emptyList())
        val decoded = WimHofPrefs.decodeHistory(WimHofPrefs.encodeHistory(listOf(original))).single()
        assertNull(decoded.journaledTo)
        assertEquals(emptyList<Int>(), decoded.retentionSeconds)
    }

    // ── Bad input ───────────────────────────────────────────────────────────

    @Test fun an_empty_blob_decodes_to_nothing() {
        assertEquals(emptyList<WimHofSessionRecord>(), WimHofPrefs.decodeHistory(""))
        assertEquals(emptyList<WimHofSessionRecord>(), WimHofPrefs.decodeHistory("   "))
    }

    @Test fun garbage_decodes_to_nothing_rather_than_throwing() {
        // A corrupted pref must not take the screen down on launch.
        assertEquals(emptyList<WimHofSessionRecord>(), WimHofPrefs.decodeHistory("not json at all"))
        assertEquals(emptyList<WimHofSessionRecord>(), WimHofPrefs.decodeHistory("{\"unexpected\":\"object\"}"))
    }

    @Test fun a_partial_entry_decodes_with_safe_defaults() {
        val decoded = WimHofPrefs.decodeHistory("[{\"startedAt\":42}]").single()
        assertEquals(42L, decoded.startedAtEpochSec)
        assertEquals(0, decoded.completedRounds)
        assertEquals(emptyList<Int>(), decoded.retentionSeconds)
        assertNull(decoded.journaledTo)
    }

    @Test fun non_object_array_entries_are_skipped_not_fatal() {
        val decoded = WimHofPrefs.decodeHistory("[1, \"two\", {\"startedAt\":7}]")
        assertEquals(1, decoded.size)
        assertEquals(7L, decoded.single().startedAtEpochSec)
    }

    // ── Pruning ─────────────────────────────────────────────────────────────

    @Test fun decoding_caps_the_history_at_the_limit() {
        // The blob is bounded on write, but an old or hand-edited pref must not grow unbounded on read.
        val many = (1..WimHofPrefs.HISTORY_LIMIT + 20).map { record(started = it.toLong()) }
        val decoded = WimHofPrefs.decodeHistory(WimHofPrefs.encodeHistory(many))
        assertEquals(WimHofPrefs.HISTORY_LIMIT, decoded.size)
        // The cap keeps the FRONT of the list, which is where the newest sessions are prepended.
        assertEquals(1L, decoded.first().startedAtEpochSec)
    }

    @Test fun best_hold_is_the_max_across_a_record() {
        assertEquals(91, record(holds = listOf(62, 91, 84)).bestRetentionSeconds)
        assertNull(record(holds = emptyList()).bestRetentionSeconds)
    }

    // ── Retention ladder codec ──────────────────────────────────────────────

    @Test fun the_fixed_ladder_round_trips() {
        val targets = listOf(60, 90, 120)
        assertEquals(targets, WimHofPrefs.decodeTargets(WimHofPrefs.encodeTargets(targets)))
    }

    @Test fun a_missing_or_unusable_ladder_falls_back_to_the_defaults() {
        assertEquals(RetentionMode.DEFAULT_FIXED_TARGETS, WimHofPrefs.decodeTargets(null))
        assertEquals(RetentionMode.DEFAULT_FIXED_TARGETS, WimHofPrefs.decodeTargets(""))
        assertEquals(RetentionMode.DEFAULT_FIXED_TARGETS, WimHofPrefs.decodeTargets("nonsense"))
    }

    @Test fun unparseable_entries_are_dropped_from_an_otherwise_good_ladder() {
        assertEquals(listOf(60, 120), WimHofPrefs.decodeTargets("60,oops,120"))
    }

    @Test fun whitespace_around_ladder_entries_is_tolerated() {
        assertEquals(listOf(60, 90), WimHofPrefs.decodeTargets(" 60 , 90 "))
    }

    // ── Audio mode cycle ────────────────────────────────────────────────────

    @Test fun the_audio_mode_chip_cycles_through_every_mode_and_returns() {
        var mode = WimHofAudioMode.Tones
        val seen = mutableListOf(mode)
        repeat(WimHofAudioMode.entries.size - 1) {
            mode = mode.next()
            seen.add(mode)
        }
        assertEquals(WimHofAudioMode.entries.toList(), seen)
        assertEquals(WimHofAudioMode.Tones, mode.next())
    }

    @Test fun the_default_audio_mode_is_audible_but_not_spoken() {
        // Tones need no TTS engine and no download, so they are the safe default on any phone.
        assertTrue(WimHofAudioMode.entries.first() == WimHofAudioMode.Tones)
    }
}
