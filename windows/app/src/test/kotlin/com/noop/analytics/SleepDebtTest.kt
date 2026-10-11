package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SleepDebt] — the rolling sleep-debt ledger: window cap,
 * skip-no-data rule, Σ(slept − need) accumulation, debt/surplus flags, and
 * the Swift-parity round1 half-away-from-zero tie-break.
 */
class SleepDebtTest {

    // ── ledger: empty / single-night ───────────────────────────────────────

    @Test fun ledger_emptySeries() {
        val led = SleepDebt.ledger(emptyList())
        assertEquals(0.0, led.balanceMin, 0.001)
        assertTrue(led.nights.isEmpty())
        assertEquals(0, led.nightCount)
    }

    @Test fun ledger_singleNightAtNeed() {
        // 8h sleep (480 min) vs 8h need (480 min) → balance 0
        val led = SleepDebt.ledger(listOf("n1" to 480.0), needHours = 8.0)
        assertEquals(0.0, led.balanceMin, 0.001)
        assertEquals(1, led.nightCount)
    }

    @Test fun ledger_singleNightDeficit() {
        // 6h sleep (360 min) vs 8h need (480 min) → balance = -120 min
        val led = SleepDebt.ledger(listOf("n1" to 360.0), needHours = 8.0)
        assertEquals(-120.0, led.balanceMin, 0.001)
    }

    @Test fun ledger_singleNightSurplus() {
        // 10h sleep (600 min) vs 8h need (480 min) → balance = +120 min
        val led = SleepDebt.ledger(listOf("n1" to 600.0), needHours = 8.0)
        assertEquals(120.0, led.balanceMin, 0.001)
    }

    // ── ledger: skip-null & window cap ─────────────────────────────────────

    @Test fun ledger_skipsNullNights() {
        // Null nights are skipped, not zero-filled: 2 usable nights each at need.
        val series = listOf<Pair<String, Double?>>(
            "n1" to null, "n2" to 480.0, "n3" to null, "n4" to 480.0,
        )
        val led = SleepDebt.ledger(series, needHours = 8.0)
        assertEquals(2, led.nightCount)
        assertEquals(0.0, led.balanceMin, 0.001)
    }

    @Test fun ledger_windowCapsNights() {
        // 20 nights each 6h (−120 min/night), window=14 → only the most-recent 14
        // counted: balance = 14 × −120 = −1680 min.
        val series = (1..20).map { "n$it" to 360.0 }
        val led = SleepDebt.ledger(series, needHours = 8.0, window = 14)
        assertEquals(14, led.nightCount)
        assertEquals(-1680.0, led.balanceMin, 0.001)
    }

    // ── isDebt / magnitudeMin ───────────────────────────────────────────────

    @Test fun ledger_isDebt_true() {
        val led = SleepDebt.ledger(listOf("n1" to 360.0), needHours = 8.0)
        assertTrue(led.isDebt)
    }

    @Test fun ledger_isDebt_false() {
        val led = SleepDebt.ledger(listOf("n1" to 600.0), needHours = 8.0)
        assertFalse(led.isDebt)
    }

    @Test fun ledger_magnitudeMin() {
        // Deficit of 120 min → magnitude 120; surplus of 120 → magnitude 120.
        val deficit = SleepDebt.ledger(listOf("n1" to 360.0), needHours = 8.0)
        assertEquals(120.0, deficit.magnitudeMin, 0.001)
        val surplus = SleepDebt.ledger(listOf("n1" to 600.0), needHours = 8.0)
        assertEquals(120.0, surplus.magnitudeMin, 0.001)
    }

    // ── round1 (Swift-parity half-away-from-zero) ──────────────────────────

    @Test fun round1_negativeHalfTieRoundsAwayFromZero() {
        // −0.05 × 10 = −0.5; ceil(−0.5 − 0.5) = ceil(−1.0) = −1.0 → −0.1
        assertEquals(-0.1, SleepDebt.round1(-0.05), 1e-9)
    }
}
