package com.noop.analytics

import kotlin.math.roundToInt

/*
 * WimHofSession.kt — the Wim Hof breathwork protocol as a PURE, deterministic state machine.
 *
 * Why this is NOT another Breathe `Pace` preset: Breathe is a DOWNSHIFT protocol (slow, long exhale,
 * 4.5–7 br/min, one continuous session, HRV climbing). Wim Hof is its opposite — fast inhale-led power
 * breathing at ~25–30 br/min, then a breath HOLD on empty lungs of user-chosen length, then a recovery
 * hold on FULL lungs, repeated for a few rounds. It has structure (rounds × breaths), a variable-length
 * phase the user ends by tapping, and a completion rule that decides what reaches the journal. None of
 * that fits a `Pace` enum, so it lives here as its own engine.
 *
 * PURE + unit-tested, like [BreathPacer] and [HrDownPacer]: no Android imports, no I/O, no clocks. The
 * screen owns the wall clock and feeds this engine [Event]s; the engine owns "what phase are we in and
 * how many rounds are genuinely finished". Identical inputs always yield an identical phase sequence,
 * so the journal number is reproducible and testable without a device.
 *
 * ANDROID-ONLY (no Swift twin): this is a Choop feature, not a NOOP parity port. Note in particular
 * that it deliberately does NOT reuse [BreathPacer.schedule] — that pacer clamps to 3–12 br/min and its
 * cue list is pinned byte-for-byte against BreathPacerTests.swift as a cross-platform contract. Power
 * breathing needs ~27 br/min, so widening the shared clamp would break that parity for a feature only
 * one platform has. The cycle split is re-derived locally instead (a handful of lines), and the shared
 * pacer is left exactly as the Swift side expects it.
 *
 * SAFETY: hyperventilation lowers blood CO2 and CAN cause fainting. The engine has no opinion about
 * that — the UI (WimHofScreen) owns the safety gate. What the engine does guarantee is that it never
 * *pushes* the user: the retention phase can always be ended by the user, and no phase is unbounded.
 */

/** Which part of the protocol the session is currently in. */
enum class WimHofPhase {
    /** Settle in. A short countdown before the first breath so nobody starts mid-scroll. */
    PREPARE,

    /** Power breathing: [WimHofConfig.breathsPerRound] fast, inhale-led breaths. */
    BREATHING,

    /** Breath hold on EMPTY lungs, after the last exhale of the round. The heart of the method. */
    RETENTION,

    /** Recovery breath: one deep inhale, then hold on FULL lungs for a fixed count. */
    RECOVERY_HOLD,

    /** All rounds finished (or the user stopped). Terminal. */
    DONE,
}

/**
 * How the retention hold is timed.
 *
 * [TapToEnd] is the authentic form — hold as long as is comfortable and tap when you need to breathe;
 * the achieved time is the interesting number. [Fixed] counts DOWN to a per-round target for people who
 * prefer a predictable session; it auto-advances when the target is reached.
 */
sealed class RetentionMode {
    /** Hold until the user taps. [maxSeconds] is a safety ceiling, never a goal — the phase auto-ends
     *  there so a session can't hang forever if the phone is put down mid-hold. */
    data class TapToEnd(val maxSeconds: Int = DEFAULT_MAX_RETENTION_SECONDS) : RetentionMode()

    /** Count down to a target per round. [targetsSeconds] is read by round index; the LAST entry is
     *  reused for any round beyond the list, so a 2-entry list still drives a 5-round session. */
    data class Fixed(val targetsSeconds: List<Int>) : RetentionMode()

    companion object {
        /** The ceiling on a tap-to-end hold. Well beyond any ordinary retention (elite holds sit
         *  around 3–4 minutes), so it never truncates a real hold — it only stops a forgotten phone. */
        const val DEFAULT_MAX_RETENTION_SECONDS: Int = 300

        /** The default fixed-mode ladder: holds lengthen as CO2 tolerance builds across rounds. */
        val DEFAULT_FIXED_TARGETS: List<Int> = listOf(60, 90, 120)
    }
}

/**
 * One session's settings. Every field is clamped by [WimHofConfig.sanitised] before use, so a malformed
 * stored pref (or a stepper bug) can never produce a trapping or absurd session.
 */
data class WimHofConfig(
    /** How many rounds to guide. Default 3 — the canonical session. */
    val rounds: Int = DEFAULT_ROUNDS,
    /** Power breaths per round. Default 30 — the canonical count. */
    val breathsPerRound: Int = DEFAULT_BREATHS_PER_ROUND,
    /** One full power breath (in + out) in ms. Default 2200 ms ≈ 27 breaths/min. */
    val breathCycleMs: Int = DEFAULT_BREATH_CYCLE_MS,
    /** Share of the cycle spent inhaling. Power breathing is INHALE-led (the exhale is a passive
     *  "let go"), the mirror image of Breathe's calming 0.4 long-exhale split. */
    val inhaleFraction: Double = DEFAULT_INHALE_FRACTION,
    /** Settle-in countdown before the first breath. */
    val prepareSeconds: Int = DEFAULT_PREPARE_SECONDS,
    /** Hold-on-full-lungs after the recovery inhale. 15 s is the method's own figure. */
    val recoveryHoldSeconds: Int = DEFAULT_RECOVERY_HOLD_SECONDS,
    /** How the retention hold is timed. */
    val retention: RetentionMode = RetentionMode.TapToEnd(),
) {
    /** This config with every field forced into its supported range. Call once at session start. */
    fun sanitised(): WimHofConfig = copy(
        rounds = rounds.coerceIn(MIN_ROUNDS, MAX_ROUNDS),
        breathsPerRound = breathsPerRound.coerceIn(MIN_BREATHS_PER_ROUND, MAX_BREATHS_PER_ROUND),
        breathCycleMs = breathCycleMs.coerceIn(MIN_BREATH_CYCLE_MS, MAX_BREATH_CYCLE_MS),
        inhaleFraction = inhaleFraction.coerceIn(MIN_INHALE_FRACTION, MAX_INHALE_FRACTION),
        prepareSeconds = prepareSeconds.coerceIn(0, MAX_PREPARE_SECONDS),
        recoveryHoldSeconds = recoveryHoldSeconds.coerceIn(MIN_RECOVERY_HOLD_SECONDS, MAX_RECOVERY_HOLD_SECONDS),
        retention = when (retention) {
            is RetentionMode.TapToEnd -> RetentionMode.TapToEnd(
                retention.maxSeconds.coerceIn(MIN_RETENTION_SECONDS, MAX_RETENTION_SECONDS),
            )
            is RetentionMode.Fixed -> RetentionMode.Fixed(
                retention.targetsSeconds
                    .map { it.coerceIn(MIN_RETENTION_SECONDS, MAX_RETENTION_SECONDS) }
                    .ifEmpty { RetentionMode.DEFAULT_FIXED_TARGETS },
            )
        },
    )

    /** Breaths/min this cycle length works out to — the honest pace readout for the setup screen. */
    val breathsPerMinute: Double get() = 60_000.0 / breathCycleMs.coerceAtLeast(1)

    /** Inhale / exhale split of one power breath, in ms. Integer so the UI clock can't drift off it. */
    val inhaleMs: Int get() = (breathCycleMs * inhaleFraction).roundToInt()
    val exhaleMs: Int get() = breathCycleMs - inhaleMs

    companion object {
        const val DEFAULT_ROUNDS = 3
        const val DEFAULT_BREATHS_PER_ROUND = 30
        const val DEFAULT_BREATH_CYCLE_MS = 2_200
        const val DEFAULT_INHALE_FRACTION = 0.55
        const val DEFAULT_PREPARE_SECONDS = 10
        const val DEFAULT_RECOVERY_HOLD_SECONDS = 15

        const val MIN_ROUNDS = 1
        const val MAX_ROUNDS = 10
        const val MIN_BREATHS_PER_ROUND = 5
        const val MAX_BREATHS_PER_ROUND = 60
        const val MIN_BREATH_CYCLE_MS = 1_200
        const val MAX_BREATH_CYCLE_MS = 4_000
        const val MIN_INHALE_FRACTION = 0.3
        const val MAX_INHALE_FRACTION = 0.8
        const val MAX_PREPARE_SECONDS = 60
        const val MIN_RECOVERY_HOLD_SECONDS = 5
        const val MAX_RECOVERY_HOLD_SECONDS = 30
        const val MIN_RETENTION_SECONDS = 5
        const val MAX_RETENTION_SECONDS = 600
    }
}

/**
 * The live session state. Immutable — [WimHofSession.advance] returns a new one, so the Compose screen
 * can hold it in a single `mutableStateOf` and every transition is a value change it can recompose on.
 *
 * [roundIndex] is 0-based and points at the round currently being WORKED, which is not the same as the
 * number finished: see [completedRounds].
 */
data class WimHofState(
    val config: WimHofConfig,
    val phase: WimHofPhase,
    /** 0-based index of the round in progress. */
    val roundIndex: Int = 0,
    /** Milliseconds elapsed inside the current phase. The screen advances this with its own clock. */
    val phaseElapsedMs: Int = 0,
    /** Achieved retention seconds, appended once per finished round, oldest first. */
    val retentionSeconds: List<Int> = emptyList(),
    /** Rounds carried all the way through their recovery hold. THIS is what the journal counts. */
    val completedRounds: Int = 0,
) {
    /** True once the session is over (all rounds done, or the user stopped). */
    val isDone: Boolean get() = phase == WimHofPhase.DONE

    /** Longest hold this session, or null before the first round finishes. */
    val bestRetentionSeconds: Int? get() = retentionSeconds.maxOrNull()

    /**
     * Power breaths finished in THIS round so far (0..breathsPerRound) — DERIVED from the phase clock
     * rather than stored, so the on-screen "12 / 30" counter is always exactly in step with the vessel
     * animation driven by the same [phaseElapsedMs]. A stored counter would have to be incremented on
     * its own schedule and could drift a breath away from the thing the user is watching.
     * Zero outside [WimHofPhase.BREATHING]; the round's full count once the round's breathing is over.
     */
    val breathsDone: Int
        get() = when (phase) {
            WimHofPhase.BREATHING ->
                (phaseElapsedMs / config.breathCycleMs.coerceAtLeast(1)).coerceIn(0, config.breathsPerRound)
            WimHofPhase.RETENTION, WimHofPhase.RECOVERY_HOLD -> config.breathsPerRound
            else -> 0
        }

    /**
     * 0..1 progress through the CURRENT breath cycle, and whether we're inhaling — the two numbers the
     * on-screen vessel animation is driven from. Meaningful only in [WimHofPhase.BREATHING].
     */
    val breathCyclePosition: Double
        get() = if (config.breathCycleMs <= 0) 0.0
        else (phaseElapsedMs % config.breathCycleMs).toDouble() / config.breathCycleMs

    /** True while the current power breath is in its inhale half. */
    val isInhaling: Boolean
        get() = (phaseElapsedMs % config.breathCycleMs.coerceAtLeast(1)) < config.inhaleMs

    /**
     * How full the lungs should read, 0..1 — the value the liquid vessel renders directly.
     * Rises linearly across the inhale, falls across the exhale, sits FULL through the recovery hold and
     * EMPTY through the retention, which is exactly the physical truth of each phase.
     */
    val lungFill: Double
        get() = when (phase) {
            WimHofPhase.PREPARE -> 0.35            // a calm, half-settled resting pose
            WimHofPhase.BREATHING -> {
                val inCycle = phaseElapsedMs % config.breathCycleMs.coerceAtLeast(1)
                if (inCycle < config.inhaleMs) {
                    inCycle.toDouble() / config.inhaleMs.coerceAtLeast(1)
                } else {
                    1.0 - (inCycle - config.inhaleMs).toDouble() / config.exhaleMs.coerceAtLeast(1)
                }.coerceIn(0.0, 1.0)
            }
            WimHofPhase.RETENTION -> 0.0           // holding on empty
            WimHofPhase.RECOVERY_HOLD -> 1.0       // holding on full
            WimHofPhase.DONE -> 0.35
        }
}

/** Things that happen TO a session. The screen translates its clock and the user's taps into these. */
sealed class WimHofEvent {
    /** [deltaMs] of wall time passed. The only driver of automatic phase changes. */
    data class Tick(val deltaMs: Int) : WimHofEvent()

    /** The user tapped the big target — ends a tap-to-end retention, or skips ahead out of PREPARE. */
    object Advance : WimHofEvent()

    /** The user stopped the session. Rounds already completed are KEPT (they really happened). */
    object Stop : WimHofEvent()
}

object WimHofSession {

    /** A fresh session at its first phase. [config] is sanitised here so callers can't skip it. */
    fun start(config: WimHofConfig): WimHofState {
        val safe = config.sanitised()
        return WimHofState(
            config = safe,
            // A zero-length prepare drops straight into the first breath rather than showing an
            // instantly-finished countdown.
            phase = if (safe.prepareSeconds > 0) WimHofPhase.PREPARE else WimHofPhase.BREATHING,
        )
    }

    /**
     * Apply one [event] and return the next state.
     *
     * The completion rule the whole feature rests on: **a round counts only once its recovery hold has
     * finished.** Stopping (or timing out) part-way through a round never increments [WimHofState
     * .completedRounds], so an abandoned round cannot inflate what gets written to the journal. That is
     * the user's stated requirement — "only full completed rounds count" — expressed in one place.
     *
     * A [WimHofEvent.Tick] can cross more than one phase boundary if a big delta arrives (the screen was
     * briefly starved, say), so each phase's overflow is carried into the next rather than discarded.
     */
    fun advance(state: WimHofState, event: WimHofEvent): WimHofState {
        if (state.isDone) return state
        return when (event) {
            is WimHofEvent.Stop -> state.copy(phase = WimHofPhase.DONE)
            is WimHofEvent.Advance -> advanceManually(state)
            is WimHofEvent.Tick -> tick(state, event.deltaMs.coerceAtLeast(0))
        }
    }

    /** The user tapped through: skip the settle-in, or end a tap-to-end hold now. */
    private fun advanceManually(state: WimHofState): WimHofState = when (state.phase) {
        WimHofPhase.PREPARE -> state.copy(phase = WimHofPhase.BREATHING, phaseElapsedMs = 0)
        WimHofPhase.RETENTION -> finishRetention(state)
        // Breathing and the recovery hold are fixed-length by design — tapping through them would
        // let a "completed" round mean nothing. Ignored, so a stray tap can't fake a round.
        else -> state
    }

    private fun tick(state: WimHofState, deltaMs: Int): WimHofState {
        var s = state.copy(phaseElapsedMs = state.phaseElapsedMs + deltaMs)
        // Loop so one oversized delta can carry through several short phases without losing time.
        var guard = 0
        while (guard++ < MAX_PHASE_CROSSINGS_PER_TICK) {
            val next = settle(s) ?: return s
            s = next
            if (s.isDone) return s
        }
        return s
    }

    /**
     * If the current phase's budget is spent, move to the next one and carry the overflow. Returns null
     * when the phase still has time left (the settle loop then stops).
     */
    private fun settle(s: WimHofState): WimHofState? {
        val budget = phaseBudgetMs(s) ?: return null   // null budget = user-ended phase, never expires
        if (s.phaseElapsedMs < budget) return null
        val overflow = s.phaseElapsedMs - budget
        return when (s.phase) {
            WimHofPhase.PREPARE ->
                s.copy(phase = WimHofPhase.BREATHING, phaseElapsedMs = overflow)

            WimHofPhase.BREATHING ->
                s.copy(phase = WimHofPhase.RETENTION, phaseElapsedMs = overflow)

            // A tap-to-end hold that reaches its ceiling is treated exactly like a tap: the hold really
            // happened, so the round can still complete.
            WimHofPhase.RETENTION -> finishRetention(s).copy(phaseElapsedMs = overflow)

            WimHofPhase.RECOVERY_HOLD -> {
                // THE round-completion point — the only place completedRounds ever increases.
                val done = s.completedRounds + 1
                if (done >= s.config.rounds) {
                    s.copy(phase = WimHofPhase.DONE, phaseElapsedMs = 0, completedRounds = done)
                } else {
                    s.copy(
                        phase = WimHofPhase.BREATHING,
                        roundIndex = s.roundIndex + 1,
                        phaseElapsedMs = overflow,
                        completedRounds = done,
                    )
                }
            }

            WimHofPhase.DONE -> null
        }
    }

    /** Bank the achieved hold and move into the recovery breath. */
    private fun finishRetention(s: WimHofState): WimHofState = s.copy(
        phase = WimHofPhase.RECOVERY_HOLD,
        phaseElapsedMs = 0,
        retentionSeconds = s.retentionSeconds + (s.phaseElapsedMs / 1000),
    )

    /**
     * How long the current phase lasts, in ms. Null means "until the user acts" — only a tap-to-end
     * retention is like that, and even it carries a ceiling (see [RetentionMode.TapToEnd.maxSeconds]),
     * so in practice every phase does terminate.
     */
    fun phaseBudgetMs(s: WimHofState): Int? = when (s.phase) {
        WimHofPhase.PREPARE -> s.config.prepareSeconds * 1000
        WimHofPhase.BREATHING -> s.config.breathsPerRound * s.config.breathCycleMs
        WimHofPhase.RETENTION -> retentionBudgetSeconds(s.config, s.roundIndex) * 1000
        WimHofPhase.RECOVERY_HOLD -> s.config.recoveryHoldSeconds * 1000
        WimHofPhase.DONE -> null
    }

    /**
     * The retention budget for [roundIndex]: the fixed-mode target, or the tap-to-end ceiling. Fixed
     * ladders shorter than the round count reuse their last entry, so a 3-target ladder still drives a
     * 5-round session sensibly instead of falling off the end.
     */
    fun retentionBudgetSeconds(config: WimHofConfig, roundIndex: Int): Int =
        when (val r = config.retention) {
            is RetentionMode.TapToEnd -> r.maxSeconds
            is RetentionMode.Fixed ->
                r.targetsSeconds.getOrNull(roundIndex)
                    ?: r.targetsSeconds.lastOrNull()
                    ?: RetentionMode.DEFAULT_FIXED_TARGETS.last()
        }

    /**
     * Rough session length in seconds, for the "about N minutes" line on the setup screen. Tap-to-end
     * holds are unknowable in advance, so [assumedRetentionSeconds] stands in for them — the UI must
     * present the result as an estimate, never a promise.
     */
    fun estimatedDurationSeconds(
        config: WimHofConfig,
        assumedRetentionSeconds: Int = TYPICAL_RETENTION_SECONDS,
    ): Int {
        val c = config.sanitised()
        val perRoundBreathing = c.breathsPerRound * c.breathCycleMs / 1000
        var total = c.prepareSeconds
        for (round in 0 until c.rounds) {
            val hold = when (c.retention) {
                is RetentionMode.TapToEnd -> assumedRetentionSeconds
                is RetentionMode.Fixed -> retentionBudgetSeconds(c, round)
            }
            total += perRoundBreathing + hold + c.recoveryHoldSeconds
        }
        return total
    }

    /** A middling first-timer hold, used only to estimate a tap-to-end session's length. */
    const val TYPICAL_RETENTION_SECONDS: Int = 75

    /** Safety valve on the settle loop so a pathological config can never spin forever. */
    private const val MAX_PHASE_CROSSINGS_PER_TICK = 64
}
