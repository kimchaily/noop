package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [WimHofSession] — the Wim Hof protocol state machine.
 *
 * The load-bearing behaviour, and the reason most of these tests exist: **a round counts only once its
 * recovery hold finishes.** That number is what gets written into the user's journal, so an abandoned
 * round inflating it would silently corrupt their record. Several tests below attack that rule from
 * different directions.
 */
class WimHofSessionTest {

    /** A deliberately tiny session so a full run is a handful of ticks. */
    private fun tinyConfig(
        rounds: Int = 2,
        breaths: Int = 5,
        retention: RetentionMode = RetentionMode.Fixed(listOf(10, 10)),
    ) = WimHofConfig(
        rounds = rounds,
        breathsPerRound = breaths,
        breathCycleMs = 2_000,
        prepareSeconds = 0,
        recoveryHoldSeconds = 5,
        retention = retention,
    )

    /** Drive [ms] of wall time through the engine in realistic 50 ms slices. */
    private fun run(state: WimHofState, ms: Int, sliceMs: Int = 50): WimHofState {
        var s = state
        var elapsed = 0
        while (elapsed < ms) {
            val step = minOf(sliceMs, ms - elapsed)
            s = WimHofSession.advance(s, WimHofEvent.Tick(step))
            elapsed += step
        }
        return s
    }

    // ── Phase sequence ──────────────────────────────────────────────────────

    @Test fun starts_in_prepare_when_a_countdown_is_configured() {
        val s = WimHofSession.start(WimHofConfig(prepareSeconds = 10))
        assertEquals(WimHofPhase.PREPARE, s.phase)
    }

    @Test fun skips_prepare_entirely_when_it_is_zero() {
        val s = WimHofSession.start(WimHofConfig(prepareSeconds = 0))
        assertEquals(WimHofPhase.BREATHING, s.phase)
    }

    @Test fun walks_breathing_then_retention_then_recovery() {
        var s = WimHofSession.start(tinyConfig())
        assertEquals(WimHofPhase.BREATHING, s.phase)

        // 5 breaths × 2000 ms of breathing (5 is the engine's minimum, so nothing is clamped here).
        s = run(s, 5 * 2_000)
        assertEquals(WimHofPhase.RETENTION, s.phase)

        // 10 s fixed hold.
        s = run(s, 10_000)
        assertEquals(WimHofPhase.RECOVERY_HOLD, s.phase)
        // NOT yet counted — the recovery hold has not finished.
        assertEquals(0, s.completedRounds)

        // 5 s recovery hold closes round 1.
        s = run(s, 5_000)
        assertEquals(1, s.completedRounds)
        assertEquals(WimHofPhase.BREATHING, s.phase)
        assertEquals(1, s.roundIndex)
    }

    @Test fun a_full_two_round_session_ends_done_with_both_rounds_counted() {
        val config = tinyConfig()
        val perRoundMs = 5 * 2_000 + 10_000 + 5_000
        val s = run(WimHofSession.start(config), perRoundMs * 2)
        assertTrue(s.isDone)
        assertEquals(2, s.completedRounds)
        assertEquals(2, s.retentionSeconds.size)
    }

    // ── The completion rule ─────────────────────────────────────────────────

    @Test fun stopping_during_the_final_retention_counts_only_the_finished_rounds() {
        val config = tinyConfig(rounds = 3, retention = RetentionMode.Fixed(listOf(10, 10, 10)))
        val perRoundMs = 5 * 2_000 + 10_000 + 5_000
        // Two full rounds, then part-way into round three's hold.
        var s = run(WimHofSession.start(config), perRoundMs * 2 + 5 * 2_000 + 4_000)
        assertEquals(WimHofPhase.RETENTION, s.phase)
        assertEquals(2, s.completedRounds)

        s = WimHofSession.advance(s, WimHofEvent.Stop)
        assertTrue(s.isDone)
        // THE contract: an abandoned third round must not reach the journal.
        assertEquals(2, s.completedRounds)
    }

    @Test fun stopping_during_the_recovery_hold_does_not_count_that_round() {
        val config = tinyConfig(rounds = 2)
        var s = run(WimHofSession.start(config), 5 * 2_000 + 10_000 + 2_000)
        assertEquals(WimHofPhase.RECOVERY_HOLD, s.phase)
        s = WimHofSession.advance(s, WimHofEvent.Stop)
        assertEquals(0, s.completedRounds)
    }

    @Test fun stopping_immediately_counts_nothing() {
        val s = WimHofSession.advance(WimHofSession.start(tinyConfig()), WimHofEvent.Stop)
        assertTrue(s.isDone)
        assertEquals(0, s.completedRounds)
    }

    @Test fun a_stopped_session_ignores_further_events() {
        val stopped = WimHofSession.advance(WimHofSession.start(tinyConfig()), WimHofEvent.Stop)
        val after = WimHofSession.advance(stopped, WimHofEvent.Tick(60_000))
        assertEquals(stopped, after)
    }

    @Test fun tapping_through_breathing_cannot_fake_a_completed_round() {
        // Advance is only meaningful in PREPARE and RETENTION. If it skipped the breathing phase, a
        // user could tap a session to "complete" in seconds and log rounds they never did.
        val s = WimHofSession.start(tinyConfig())
        assertEquals(WimHofPhase.BREATHING, s.phase)
        val after = WimHofSession.advance(s, WimHofEvent.Advance)
        assertEquals(WimHofPhase.BREATHING, after.phase)
        assertEquals(0, after.completedRounds)
    }

    // ── Retention ───────────────────────────────────────────────────────────

    @Test fun tap_to_end_banks_the_achieved_hold_and_moves_to_recovery() {
        val config = tinyConfig(retention = RetentionMode.TapToEnd())
        var s = run(WimHofSession.start(config), 5 * 2_000)
        assertEquals(WimHofPhase.RETENTION, s.phase)

        s = run(s, 47_000)
        s = WimHofSession.advance(s, WimHofEvent.Advance)
        assertEquals(WimHofPhase.RECOVERY_HOLD, s.phase)
        assertEquals(listOf(47), s.retentionSeconds)
    }

    @Test fun a_tap_to_end_hold_still_ends_at_its_ceiling() {
        // A phone put down mid-hold must not leave the session hanging forever.
        val config = tinyConfig(retention = RetentionMode.TapToEnd(maxSeconds = 30))
        var s = run(WimHofSession.start(config), 5 * 2_000)
        assertEquals(WimHofPhase.RETENTION, s.phase)
        s = run(s, 31_000)
        assertNotEquals(WimHofPhase.RETENTION, s.phase)
        assertEquals(listOf(30), s.retentionSeconds)
    }

    @Test fun fixed_targets_are_read_per_round_and_the_last_one_repeats() {
        val config = WimHofConfig(retention = RetentionMode.Fixed(listOf(60, 90)))
        assertEquals(60, WimHofSession.retentionBudgetSeconds(config, 0))
        assertEquals(90, WimHofSession.retentionBudgetSeconds(config, 1))
        // Beyond the ladder: reuse the last entry rather than falling off the end.
        assertEquals(90, WimHofSession.retentionBudgetSeconds(config, 2))
        assertEquals(90, WimHofSession.retentionBudgetSeconds(config, 9))
    }

    @Test fun an_empty_fixed_ladder_falls_back_to_the_defaults() {
        val config = WimHofConfig(retention = RetentionMode.Fixed(emptyList())).sanitised()
        assertEquals(RetentionMode.DEFAULT_FIXED_TARGETS, (config.retention as RetentionMode.Fixed).targetsSeconds)
    }

    // ── Derived display values ──────────────────────────────────────────────

    @Test fun breaths_done_tracks_the_phase_clock() {
        val config = tinyConfig(breaths = 10)
        var s = WimHofSession.start(config)
        assertEquals(0, s.breathsDone)
        s = run(s, 2_000)      // one full breath
        assertEquals(1, s.breathsDone)
        s = run(s, 2_000)      // two
        assertEquals(2, s.breathsDone)
    }

    @Test fun lung_fill_rises_on_the_inhale_and_falls_on_the_exhale() {
        val config = WimHofConfig(
            breathsPerRound = 10, breathCycleMs = 2_000, inhaleFraction = 0.5, prepareSeconds = 0,
        )
        var s = WimHofSession.start(config)
        s = run(s, 500)                       // quarter-cycle: mid-inhale
        assertTrue(s.isInhaling)
        val midInhale = s.lungFill
        assertTrue("mid-inhale fill should be part way up, was $midInhale", midInhale in 0.3..0.7)

        s = run(s, 1_000)                     // three-quarters: mid-exhale
        assertFalse(s.isInhaling)
        assertTrue("mid-exhale should sit below the inhale peak", s.lungFill < 1.0)
    }

    @Test fun lung_fill_is_empty_while_holding_out_and_full_while_holding_in() {
        var s = run(WimHofSession.start(tinyConfig()), 5 * 2_000)
        assertEquals(WimHofPhase.RETENTION, s.phase)
        assertEquals(0.0, s.lungFill, 1e-9)

        s = run(s, 10_000)
        assertEquals(WimHofPhase.RECOVERY_HOLD, s.phase)
        assertEquals(1.0, s.lungFill, 1e-9)
    }

    // ── Config hygiene ──────────────────────────────────────────────────────

    @Test fun config_is_clamped_into_supported_ranges() {
        val wild = WimHofConfig(
            rounds = 999, breathsPerRound = 0, breathCycleMs = 1, inhaleFraction = 5.0,
            prepareSeconds = -5, recoveryHoldSeconds = 9999,
        ).sanitised()
        assertEquals(WimHofConfig.MAX_ROUNDS, wild.rounds)
        assertEquals(WimHofConfig.MIN_BREATHS_PER_ROUND, wild.breathsPerRound)
        assertEquals(WimHofConfig.MIN_BREATH_CYCLE_MS, wild.breathCycleMs)
        assertEquals(WimHofConfig.MAX_INHALE_FRACTION, wild.inhaleFraction, 1e-9)
        assertEquals(0, wild.prepareSeconds)
        assertEquals(WimHofConfig.MAX_RECOVERY_HOLD_SECONDS, wild.recoveryHoldSeconds)
    }

    @Test fun start_sanitises_so_a_bad_stored_pref_cannot_trap_the_session() {
        val s = WimHofSession.start(WimHofConfig(rounds = 0, breathsPerRound = 1, breathCycleMs = 0))
        assertTrue(s.config.rounds >= WimHofConfig.MIN_ROUNDS)
        assertTrue(s.config.breathCycleMs >= WimHofConfig.MIN_BREATH_CYCLE_MS)
        // And it still terminates rather than spinning.
        assertTrue(run(s, 20 * 60 * 1_000, sliceMs = 1_000).isDone)
    }

    @Test fun the_default_session_is_three_rounds_of_thirty() {
        val c = WimHofConfig()
        assertEquals(3, c.rounds)
        assertEquals(30, c.breathsPerRound)
    }

    // ── Duration estimate ───────────────────────────────────────────────────

    @Test fun estimated_duration_sums_the_phases_for_a_fixed_ladder() {
        val config = WimHofConfig(
            rounds = 2, breathsPerRound = 30, breathCycleMs = 2_000, prepareSeconds = 10,
            recoveryHoldSeconds = 15, retention = RetentionMode.Fixed(listOf(60, 90)),
        )
        // 10 prep + (60s breathing + 60 hold + 15 recovery) + (60 + 90 + 15)
        assertEquals(10 + 135 + 165, WimHofSession.estimatedDurationSeconds(config))
    }

    @Test fun estimated_duration_uses_the_assumed_hold_for_tap_to_end() {
        val config = WimHofConfig(
            rounds = 1, breathsPerRound = 30, breathCycleMs = 2_000, prepareSeconds = 0,
            recoveryHoldSeconds = 15, retention = RetentionMode.TapToEnd(),
        )
        assertEquals(60 + 90 + 15, WimHofSession.estimatedDurationSeconds(config, assumedRetentionSeconds = 90))
    }

    // ── Tick robustness ─────────────────────────────────────────────────────

    @Test fun one_oversized_tick_carries_through_several_phases_without_losing_rounds() {
        // A starved frame or a resumed screen can deliver a large delta at once; the session must land
        // in the same place a steady stream of small ticks would have reached.
        val config = tinyConfig(rounds = 2)
        val perRoundMs = 5 * 2_000 + 10_000 + 5_000
        val bigStep = WimHofSession.advance(WimHofSession.start(config), WimHofEvent.Tick(perRoundMs * 2))
        val smallSteps = run(WimHofSession.start(config), perRoundMs * 2)
        assertEquals(smallSteps.completedRounds, bigStep.completedRounds)
        assertEquals(smallSteps.phase, bigStep.phase)
    }

    @Test fun a_negative_tick_is_ignored_rather_than_rewinding_the_clock() {
        val s = run(WimHofSession.start(tinyConfig()), 1_000)
        val after = WimHofSession.advance(s, WimHofEvent.Tick(-5_000))
        assertEquals(s.phaseElapsedMs, after.phaseElapsedMs)
    }
}
