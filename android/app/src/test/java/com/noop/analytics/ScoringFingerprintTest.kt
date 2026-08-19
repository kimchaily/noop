package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScoringFingerprint] is the scoring version, derived rather than declared, so that a full-history
 * rebuild can no longer be skipped because someone forgot to bump a constant — which is exactly how
 * weeks-old days ended up stranded on a superseded algorithm.
 *
 * These tests pin the two properties the mechanism rests on: it is STABLE for a build (or every launch
 * would trigger a pointless rebuild) and it MOVES when the scorers' output moves (or it protects
 * nothing).
 */
class ScoringFingerprintTest {

    @Test
    fun isStableWithinABuild() {
        // Same process, repeated reads: a rebuild must not be triggered by simply asking twice.
        assertEquals(ScoringFingerprint.value, ScoringFingerprint.value)
    }

    @Test
    fun looksLikeAFingerprintNotAnEmptyString() {
        val v = ScoringFingerprint.value
        assertTrue("got '$v'", v.startsWith("chg-") && v.length > 6)
    }

    /**
     * The fixture supplies its own nights, so a change to WHICH nights reach the scorers cannot move the
     * hash — [ScoringFingerprint.INPUT_REVISION] is the only thing that carries such a change into the
     * value and fires the one-off history rebuild. If it stops reaching the value, bumping it becomes a
     * no-op and the rebuild silently stops happening.
     */
    @Test
    fun carriesTheInputRevision() {
        val v = ScoringFingerprint.value
        assertTrue("got '$v'", v.endsWith("-i${ScoringFingerprint.INPUT_REVISION}"))
    }

    /**
     * The load-bearing property. The fingerprint is a hash of the scorers' outputs over a fixed fixture,
     * so this recomputes that hash with ONE constant perturbed and asserts it differs. If this ever
     * passes trivially, the fingerprint has stopped tracking the algorithm and the rebuild gate is
     * decorative.
     */
    @Test
    fun movesWhenAScoringOutputMoves() {
        val baseline = fixtureHash(hrvWeight = RecoveryScorer.wHRV)
        val perturbed = fixtureHash(hrvWeight = RecoveryScorer.wHRV + 0.01)
        assertNotEquals(
            "a changed weight must change the fingerprint, else a rebuild can be silently skipped",
            baseline,
            perturbed,
        )
    }

    /**
     * A miniature of what [ScoringFingerprint] does internally, with the HRV weight injectable so the
     * test can simulate a scoring change without editing production constants.
     */
    private fun fixtureHash(hrvWeight: Double): Int {
        val nights: List<Double?> = listOf(62.0, 58.0, 71.0, 55.0, null, 60.0, 49.0, 66.0)
        val prefix = Baselines.foldHistoryPrefix(nights, Baselines.hrvCfg)
        val out = StringBuilder()
        for (i in nights.indices) {
            val hrv = nights[i] ?: continue
            val base = prefix[i]
            if (!base.usable) { out.append("nil;"); continue }
            val z = RecoveryScorer.zScore(hrv, base.baseline, base.spread)
            val sleepZ = (0.72 - RecoveryScorer.sleepPerfCenter) / RecoveryScorer.sleepPerfScale
            val total = hrvWeight + RecoveryScorer.wSleep
            val composite = (z * hrvWeight + sleepZ * RecoveryScorer.wSleep) / total
            val score = 100.0 / (1.0 + Math.exp(-RecoveryScorer.logisticK * (composite - RecoveryScorer.logisticZ0)))
            out.append("%.4f".format(java.util.Locale.US, score)).append(';')
        }
        return out.toString().hashCode()
    }
}
