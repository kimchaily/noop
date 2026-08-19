package com.noop.analytics

/**
 * ScoringFingerprint.kt — the scoring version, DERIVED rather than declared.
 *
 * WHY THIS EXISTS
 * ---------------
 * A day's stored score is only trustworthy if it was produced by the algorithm currently in the app.
 * A normal analyze pass re-scores only a trailing window, so whenever the algorithm changes the whole
 * stored history has to be rebuilt once — otherwise recent days move onto the new definition while
 * older ones quietly keep numbers from a superseded one.
 *
 * That rebuild used to be gated on a hand-maintained constant that a developer had to remember to bump.
 * It was forgotten immediately: the first version latched on the build that introduced it, and the two
 * scoring fixes that followed each found the latch already set and skipped, leaving weeks-old days on
 * the original, wrong algorithm. A constant nobody updates is worse than no constant, because it looks
 * like protection.
 *
 * So the version is not written down here. It is COMPUTED: a fixed, tiny fixture is pushed through the
 * real scorers and the hash of their outputs is the version. Change a weight, the logistic, the baseline
 * maths, a clamp, a floor — the outputs move, the hash moves, and the rebuild triggers on its own. There
 * is nothing to remember and nothing to forget.
 *
 * WHAT IT DOES NOT CATCH — read this before trusting it
 * ----------------------------------------------------
 * The fixture supplies its own nights, so this detects changes to the FORMULA, not to WHICH NIGHTS ARE
 * FED IN. Two of the three baseline faults this app shipped were exactly that: the same arithmetic over
 * a different set of nights. This fingerprint would not have moved for either of them.
 *
 * Membership and ordering are guarded by behavioural tests instead ([ChargeCausalStabilityTest]:
 * appending a night must not disturb earlier days; dropping the oldest night must move a later one).
 * Two failure classes, two mechanisms — neither substitutes for the other. When a membership change does
 * ship, [INPUT_REVISION] is what carries it into the fingerprint so the one-off rebuild still happens.
 *
 * COST: a handful of pure function calls, once per process, behind `by lazy`. No I/O, no clock, no
 * randomness, so the value is stable across launches and identical on every device running the build.
 */
object ScoringFingerprint {

    /**
     * Covers the gap the fixture cannot see: a change to WHICH NIGHTS reach the scorers.
     *
     * Bump it when a change alters the SET of values folded into a baseline — a driver that starts being
     * persisted, a membership rule that changes, a source that joins or leaves the fold — while leaving
     * the arithmetic alone. The fixture supplies its own nights, so none of that moves the hash, and
     * without a bump the stored history would keep values the current build would no longer produce.
     *
     * Yes, this is a hand-maintained constant, the very thing the derived hash exists to avoid. It is a
     * SUPPLEMENT, not a substitute: forgetting the hash meant a real scoring change went un-rebuilt,
     * whereas forgetting this one leaves exactly the behaviour that existed before it — no worse than the
     * status quo, and the behavioural membership tests still fail loudly if the rule itself is wrong.
     *
     * History:
     *   1 , the derived hash alone
     *   2 , the nightly skin-temp mean is persisted, so its baseline folds over the whole record
     */
    const val INPUT_REVISION: Int = 2

    /** The current scoring fingerprint, e.g. "chg-1f3a9c02-i2". Stable for a given build. */
    val value: String by lazy {
        // Locale.US, like r4 below: the value is compared for equality and persisted, so a locale whose
        // digits render differently must not read as a scoring change and trigger a rebuild.
        "chg-%08x-i%d".format(java.util.Locale.US, hashOfFixtureOutputs(), INPUT_REVISION)
    }

    /**
     * A deliberately varied nightly HRV history: a settling trend, a missing night (skip-and-hold), and
     * an implausible reading (the sanity gate). Exercises the young/settled split, the Winsor clamp and
     * the spread floor, so a change to any of them moves the hash.
     */
    private val hrvNights: List<Double?> = listOf(
        62.0, 58.0, 71.0, 55.0, null, 60.0, 49.0, 66.0, 3.0, 57.0, 63.0, 52.0,
    )
    private val rhrNights: List<Double?> = listOf(
        48.0, 47.0, 51.0, 46.0, null, 49.0, 45.0, 50.0, 200.0, 47.0, 46.0, 48.0,
    )
    private val respNights: List<Double?> = listOf(
        13.0, 12.5, 14.0, 12.0, null, 13.5, 12.2, 13.8, 1.0, 12.7, 13.1, 12.4,
    )

    /**
     * Push the fixture through every scorer that contributes to a stored number, and fold the results
     * into one hash. Rounded to 4 decimals first so that pure floating-point noise (a compiler reordering
     * an addition) cannot trigger a spurious history rebuild, while any change big enough to alter a
     * displayed score is far above that threshold.
     */
    private fun hashOfFixtureOutputs(): Int {
        val out = StringBuilder()

        // ── Baselines: the prefix fold each night is scored against ────────────────────────────────
        val hrvPrefix = Baselines.foldHistoryPrefix(hrvNights, Baselines.hrvCfg)
        val rhrPrefix = Baselines.foldHistoryPrefix(rhrNights, Baselines.restingHRCfg)
        val respPrefix = Baselines.foldHistoryPrefix(respNights, Baselines.respCfg)
        for (s in hrvPrefix + rhrPrefix + respPrefix) {
            out.append(r4(s.baseline)).append(',').append(r4(s.spread)).append(',')
                .append(s.nValid).append(',').append(s.status.raw).append(';')
        }

        // ── Charge: every term present, and each optional term dropped in turn, so a change to the
        // renormalisation is caught as well as a change to a weight.
        for (i in hrvNights.indices) {
            val hrv = hrvNights[i] ?: continue
            val rhr = rhrNights[i] ?: continue
            val base = hrvPrefix[i]
            val rhrBase = rhrPrefix[i]
            val respBase = respPrefix[i].takeIf { it.usable }
            for (resp in listOf(respNights[i], null)) {
                for (sleepPerf in listOf(0.72, null)) {
                    for (skin in listOf(0.4, null)) {
                        val score = RecoveryScorer.recovery(
                            hrv = hrv,
                            rhr = rhr,
                            resp = resp,
                            hrvBaseline = RecoveryScorer.DriverBaseline(base),
                            rhrBaseline = RecoveryScorer.DriverBaseline(rhrBase),
                            respBaseline = respBase?.let { RecoveryScorer.DriverBaseline(it) },
                            sleepPerf = sleepPerf,
                            skinTempDev = skin,
                            hrvBaselineUsable = base.usable,
                        )
                        out.append(score?.let { r4(it) } ?: "nil").append(';')
                    }
                }
            }
        }

        // ── Effort: the TRIMP curve and its saturation point ───────────────────────────────────────
        for (trimp in listOf(0.0, 50.0, 100.0, 500.0, 1000.0, 3600.0, 7200.0, 12000.0)) {
            out.append(r4(StrainScorer.trimpToStrain(trimp))).append(';')
        }

        // ── The band thresholds, which decide the ring colour even when the number is unchanged ────
        for (score in listOf(0.0, 33.9, 34.0, 67.0, 67.1, 100.0)) {
            out.append(RecoveryScorer.band(score)).append(';')
        }

        return out.toString().hashCode()
    }

    private fun r4(x: Double): String = "%.4f".format(java.util.Locale.US, x)
}
