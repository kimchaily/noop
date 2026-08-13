package com.noop.ui

import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Today Weight + Steps tile fallback logic (issues #107, #150). The Calories tile
 * reads straight off DailyMetric, so the pure logic worth pinning is the two tiles with an imported
 * fallback source:
 *   - [latestWeightKg] picks the most-recent non-null body weight across the two Apple-side sources.
 *   - [weightTile] prefers that weight, else falls back to the SI profile weight with an honest
 *     "from profile" caption, always formatted through the unit toggle.
 *   - [stepsForDay] resolves the selected day's imported Apple Health / Health Connect step total —
 *     the Steps tile's fallback when the strap (e.g. a WHOOP 4.0) didn't bank an on-device count.
 */
class TodayMetricTilesTest {

    private fun appleDay(day: String, weightKg: Double?) =
        AppleDaily(deviceId = "apple-health", day = day, weightKg = weightKg)

    private fun stepsDay(deviceId: String, day: String, steps: Int?) =
        AppleDaily(deviceId = deviceId, day = day, steps = steps)

    // MARK: latestWeightKg

    @Test
    fun latestWeight_nullWhenNoSourceHasWeight() {
        val apple = listOf(appleDay("2026-01-01", null), appleDay("2026-01-02", null))
        assertNull(latestWeightKg(apple, emptyList()))
    }

    @Test
    fun latestWeight_picksTheMostRecentDay() {
        val apple = listOf(
            appleDay("2026-01-01", 80.0),
            appleDay("2026-01-05", 78.5),
            appleDay("2026-01-03", 79.0),
        )
        assertEquals(78.5, latestWeightKg(apple, emptyList())!!, 1e-9)
    }

    @Test
    fun latestWeight_skipsNullWeightDaysEvenWhenNewer() {
        // A newer day with no weight must not blank out an older real reading.
        val apple = listOf(appleDay("2026-01-02", 81.0), appleDay("2026-01-09", null))
        assertEquals(81.0, latestWeightKg(apple, emptyList())!!, 1e-9)
    }

    @Test
    fun latestWeight_unionsBothSources_mostRecentWins() {
        val apple = listOf(appleDay("2026-01-04", 80.0))
        val healthConnect = listOf(
            AppleDaily(deviceId = "health-connect", day = "2026-01-06", weightKg = 77.0),
        )
        assertEquals(77.0, latestWeightKg(apple, healthConnect)!!, 1e-9)
    }

    // MARK: weightTile

    @Test
    fun weightTile_usesLatestReading_metric() {
        val t = weightTile(latestWeightKg = 74.5, profileWeightKg = 90.0, system = UnitSystem.METRIC)
        assertEquals("74.5 kg", t.value)
        assertEquals("latest", t.caption)
    }

    @Test
    fun weightTile_usesLatestReading_imperial() {
        val t = weightTile(latestWeightKg = 100.0, profileWeightKg = 90.0, system = UnitSystem.IMPERIAL)
        // 100 kg * 2.20462 = 220.462 lb
        assertEquals("220.5 lb", t.value)
        assertEquals("latest", t.caption)
    }

    @Test
    fun weightTile_fallsBackToProfile_withHonestCaption() {
        val t = weightTile(latestWeightKg = null, profileWeightKg = 75.0, system = UnitSystem.METRIC)
        assertEquals("75.0 kg", t.value)
        assertEquals("from profile", t.caption)
    }

    @Test
    fun weightTile_profileFallbackRespectsImperial() {
        val t = weightTile(latestWeightKg = null, profileWeightKg = 75.0, system = UnitSystem.IMPERIAL)
        // 75 kg * 2.20462 = 165.3465 lb
        assertEquals("165.3 lb", t.value)
        assertEquals("from profile", t.caption)
    }

    // MARK: stepsForDay — Today Steps-tile fallback to imported Apple Health / Health Connect (#150)

    @Test
    fun stepsForDay_nullWhenNeitherSourceCoversTheDay() {
        val apple = listOf(stepsDay("apple-health", "2026-01-01", 8000))
        assertNull(stepsForDay(apple, emptyList(), "2026-01-02"))
    }

    @Test
    fun stepsForDay_returnsImportedStepsForTheSelectedDay() {
        val apple = listOf(
            stepsDay("apple-health", "2026-01-01", 8000),
            stepsDay("apple-health", "2026-01-02", 11200),
        )
        assertEquals(11200, stepsForDay(apple, emptyList(), "2026-01-02"))
    }

    @Test
    fun stepsForDay_ignoresNullStepRowsForTheDay() {
        // A row exists for the day but carries no step count → treated as absent, not zero.
        val apple = listOf(stepsDay("apple-health", "2026-01-03", null))
        assertNull(stepsForDay(apple, emptyList(), "2026-01-03"))
    }

    @Test
    fun stepsForDay_unionsBothSources_takesTheLargerForTheDay() {
        // Both Apple Health and Health Connect can report the same day; take the larger (most complete)
        // rather than summing, so we never double-count overlapping sources.
        val apple = listOf(stepsDay("apple-health", "2026-01-04", 6000))
        val hc = listOf(stepsDay("health-connect", "2026-01-04", 9500))
        assertEquals(9500, stepsForDay(apple, hc, "2026-01-04"))
    }

    // MARK: buildingHint — the unscored Effort/Rest "it's coming" caption, today-only (#527)

    @Test
    fun buildingHint_rest_today_isTheWearItTonightCopy() {
        assertEquals("Building, wear it tonight", buildingHint(KeyMetric.REST, isToday = true))
    }

    @Test
    fun buildingHint_effort_today_isTheMovesAsYouDoCopy() {
        assertEquals("Building, moves as you do", buildingHint(KeyMetric.EFFORT, isToday = true))
    }

    @Test
    fun buildingHint_pastDay_isNull_soAnUnscoredOldDayStaysABareDash() {
        // Honesty: a navigated past day with no score is missing data, not mid-calibration.
        assertNull(buildingHint(KeyMetric.REST, isToday = false))
        assertNull(buildingHint(KeyMetric.EFFORT, isToday = false))
    }

    // MARK: buildingHint — H10 extension to Charge / Blood Oxygen / Steps cold-start captions

    @Test
    fun buildingHint_charge_today_isTheWearItTonightCopy() {
        // H10: a cold-start Charge (no score, not calibrating, nothing carried) reads "building", not blank.
        assertEquals("Building, wear it tonight", buildingHint(KeyMetric.CHARGE, isToday = true))
    }

    @Test
    fun buildingHint_bloodOxygen_today_buildsLikeTheOtherOvernightVitals() {
        // H10: the overnight SpO₂ fills in from sleep, like Rest.
        assertEquals("Building, wear it tonight", buildingHint(KeyMetric.BLOOD_OXYGEN, isToday = true))
    }

    @Test
    fun buildingHint_steps_today_movesAsYouDo() {
        // H10: on-device steps accrue across the day, like Effort.
        assertEquals("Building, moves as you do", buildingHint(KeyMetric.STEPS, isToday = true))
    }

    @Test
    fun buildingHint_h10Metrics_pastDay_isNull_soAnUnscoredOldDayStaysABareDash() {
        // Honesty: a navigated past day with no value is missing data, not mid-calibration.
        for (m in listOf(KeyMetric.CHARGE, KeyMetric.BLOOD_OXYGEN, KeyMetric.STEPS)) {
            assertNull(buildingHint(m, isToday = false))
        }
    }

    @Test
    fun buildingHint_stillNull_forMetricsWithNoHonestColdStartCopy() {
        // HRV / Resting HR / Respiratory / Weight / Calories carry their own treatment; no generic hint.
        for (m in listOf(KeyMetric.HRV, KeyMetric.RESTING_HR, KeyMetric.RESPIRATORY, KeyMetric.WEIGHT, KeyMetric.CALORIES)) {
            assertNull(buildingHint(m, isToday = true))
        }
    }

    @Test
    fun buildingHint_copy_hasNoEmDash() {
        // House style: user-facing strings carry no em-dashes (the #1 AI tell).
        for (m in listOf(KeyMetric.REST, KeyMetric.EFFORT, KeyMetric.CHARGE, KeyMetric.BLOOD_OXYGEN, KeyMetric.STEPS)) {
            val hint = buildingHint(m, isToday = true)!!
            assert(!hint.contains('—')) { "buildingHint($m) must not contain an em-dash: $hint" }
        }
    }

    // MARK: restStageLowConfidence — H9. Surfaces the core ScoreConfidence rule: a high-efficiency night
    // whose deep+REM share is implausibly low is flagged low-confidence STAGING (a likely staging miss),
    // shown as a small "Estimated" badge on the Rest tile rather than faked stages. efficiency is the
    // engine's 0..1 fraction; restorative = deep+REM minutes. Never fabricated — reads only banked figures.

    private fun sleepDay(
        day: String = "2026-06-19",
        totalSleepMin: Double?,
        efficiency: Double?,
        deepMin: Double?,
        remMin: Double?,
    ) = DailyMetric(
        deviceId = "my-whoop", day = day,
        totalSleepMin = totalSleepMin, efficiency = efficiency, deepMin = deepMin, remMin = remMin,
    )

    @Test
    fun restStageLowConfidence_false_whenDayIsNull() {
        assertFalse(restStageLowConfidence(null))
    }

    @Test
    fun restStageLowConfidence_false_whenNoEfficiencyOrDuration() {
        // No banked sleep figures → nothing to judge; the badge must stand down (not assume low-confidence).
        assertFalse(restStageLowConfidence(sleepDay(totalSleepMin = null, efficiency = 0.9, deepMin = 30.0, remMin = 30.0)))
        assertFalse(restStageLowConfidence(sleepDay(totalSleepMin = 480.0, efficiency = null, deepMin = 30.0, remMin = 30.0)))
    }

    @Test
    fun restStageLowConfidence_false_forAHealthyWellStructuredNight() {
        // ~45% deep+REM on a high-efficiency night is a normal adult night — SOLID, no badge.
        val d = sleepDay(totalSleepMin = 480.0, efficiency = 0.92, deepMin = 110.0, remMin = 105.0)
        assertFalse(restStageLowConfidence(d))
    }

    @Test
    fun restStageLowConfidence_true_forHighEfficiencyButNearZeroRestorative() {
        // 0.95 efficiency (>= 0.85) yet only ~4% deep+REM (< 10%) → the H9 staging-miss flag fires.
        val d = sleepDay(totalSleepMin = 480.0, efficiency = 0.95, deepMin = 10.0, remMin = 10.0)
        assertTrue(restStageLowConfidence(d))
    }

    @Test
    fun restStageLowConfidence_false_forLowEfficiencyNightWithLowRestorative() {
        // A fragmented (low-efficiency) night legitimately carries less deep/REM, so the floor does NOT
        // apply — we don't flag a genuinely poor night as a staging miss.
        val d = sleepDay(totalSleepMin = 360.0, efficiency = 0.70, deepMin = 8.0, remMin = 8.0)
        assertFalse(restStageLowConfidence(d))
    }

    @Test
    fun restStageLowConfidence_false_whenNoStagedSleepAtAll() {
        // No deep AND no REM → the base tier isn't SOLID (it's a sparse, unstaged night with its own
        // honest treatment), so the H9 "stages estimated" badge must not appear.
        val d = sleepDay(totalSleepMin = 420.0, efficiency = 0.95, deepMin = 0.0, remMin = 0.0)
        assertFalse(restStageLowConfidence(d))
    }

    // MARK: lastScoredRecoveryDay — the #543 carry-over selector that keeps the WHOLE recovery side
    // populated at the logical-day rollover (Charge ring + HRV / resting-HR / respiratory / SpO₂ tiles +
    // Synthesis / Contributors / Readiness), instead of blanking to "No Data" while live HR ticks. This
    // pins the GATE + SELECTION shared by all those read-outs. Mirrors the iOS TodayCarryOverTests.

    private fun recDay(
        day: String,
        recovery: Double?,
        hrv: Double? = null,
        rhr: Int? = null,
        spo2: Double? = null,
        resp: Double? = null,
    ) = DailyMetric(
        deviceId = "my-whoop", day = day, recovery = recovery,
        avgHrv = hrv, restingHr = rhr, spo2Pct = spo2, respRateBpm = resp,
    )

    @Test
    fun lastScoredRecoveryDay_carriesTheFreshestScoredPriorDay_whenTodayUnscoredAndPastCalibration() {
        val days = listOf(
            recDay("2026-06-17", 60.0),
            recDay("2026-06-18", 72.0),
            recDay("2026-06-19", null), // today, not scored yet
        )
        val carried = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-19",
            isToday = true, todayScored = false, isCalibrating = false,
        )
        assertEquals("2026-06-18", carried?.day)
        assertEquals(72.0, carried?.recovery)
    }

    @Test
    fun lastScoredRecoveryDay_nothingCarried_whenTodayIsAlreadyScored() {
        val days = listOf(recDay("2026-06-18", 72.0), recDay("2026-06-19", 55.0))
        assertNull(
            lastScoredRecoveryDay(
                days, selectedDayKey = "2026-06-19",
                isToday = true, todayScored = true, isCalibrating = false,
            ),
        )
    }

    @Test
    fun lastScoredRecoveryDay_nothingCarried_whileCalibrating() {
        // Calibration owns its own "N of 4" Charge copy — the carry-over must stand down.
        val days = listOf(recDay("2026-06-18", 72.0), recDay("2026-06-19", null))
        assertNull(
            lastScoredRecoveryDay(
                days, selectedDayKey = "2026-06-19",
                isToday = true, todayScored = false, isCalibrating = true,
            ),
        )
    }

    @Test
    fun lastScoredRecoveryDay_nothingCarried_onANavigatedPastDay() {
        // A navigated past day with no score is missing data, not a rollover — never carry.
        val days = listOf(recDay("2026-06-17", 60.0), recDay("2026-06-18", 72.0))
        assertNull(
            lastScoredRecoveryDay(
                days, selectedDayKey = "2026-06-18",
                isToday = false, todayScored = false, isCalibrating = false,
            ),
        )
    }

    @Test
    fun lastScoredRecoveryDay_excludesTodaysOwnKey_soItNeverEchoesToday() {
        // Today carries vitals but no recovery — it must NOT be chosen (we'd echo today as "last night").
        val days = listOf(
            recDay("2026-06-18", 72.0),
            recDay("2026-06-19", null, hrv = 40.0),
        )
        val carried = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-19",
            isToday = true, todayScored = false, isCalibrating = false,
        )
        assertEquals("2026-06-18", carried?.day)
    }

    @Test
    fun lastScoredRecoveryDay_null_whenNoPriorDayWasEverScored() {
        // A genuinely-never-scored history carries nothing — the tiles honestly stay "No Data".
        val days = listOf(recDay("2026-06-18", null), recDay("2026-06-19", null))
        assertNull(
            lastScoredRecoveryDay(
                days, selectedDayKey = "2026-06-19",
                isToday = true, todayScored = false, isCalibrating = false,
            ),
        )
    }

    @Test
    fun lastScoredRecoveryDay_carriedRow_keepsItsOwnMissingMetricsAsNull_neverFabricated() {
        // A metric the carried night genuinely lacks (e.g. a BLE-only night with no SpO₂) stays null on
        // the carried row, so the SpO₂ tile still resolves to "No Data" rather than a fabricated number.
        val days = listOf(
            recDay("2026-06-18", 72.0, hrv = 55.0, rhr = 50, spo2 = null, resp = 14.2),
            recDay("2026-06-19", null),
        )
        val carried = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-19",
            isToday = true, todayScored = false, isCalibrating = false,
        )
        assertEquals(55.0, carried?.avgHrv)
        assertEquals(50, carried?.restingHr)
        assertNull(carried?.spo2Pct)
        assertEquals(14.2, carried?.respRateBpm)
    }

    // MARK: #547 carry-over future-day guard — a stray FUTURE-dated row (a bad strap clock wrote a day
    // past "today") must NEVER be picked as "last night"; that's exactly how #547's Today header read
    // "12 Jul". Belt-and-suspenders alongside the ingest gate + heal.

    @Test
    fun lastScoredRecoveryDay_neverCarriesAFutureDatedRow_547() {
        // A bad-clock row dated AFTER today sits at the end of the oldest→newest list; without the
        // day <= today filter, lastOrNull would pick it and surface "Last night · <future date>".
        val days = listOf(
            recDay("2026-06-18", 72.0),     // the real freshest scored prior day
            recDay("2026-07-12", 90.0),     // future-dated pollution (#547 "12 Jul")
        )
        val carried = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-19",
            isToday = true, todayScored = false, isCalibrating = false,
            today = "2026-06-19",
        )
        assertEquals("2026-06-18", carried?.day)   // the future row is skipped
        assertEquals(72.0, carried?.recovery)
    }

    @Test
    fun lastScoredRecoveryDay_carriesTodayBoundaryDay_inclusive_547() {
        // A row dated exactly "today" (e.g. the local calendar day at a just-after-midnight rollover) is
        // NOT future — the <= today bound keeps it eligible so a legitimate carry-over is not dropped.
        val days = listOf(
            recDay("2026-06-18", 60.0),
            recDay("2026-06-19", 72.0),     // == today; eligible (it isn't the selected/unscored key)
        )
        val carried = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-20",   // today's still-null logical key
            isToday = true, todayScored = false, isCalibrating = false,
            today = "2026-06-19",
        )
        assertEquals("2026-06-19", carried?.day)
        assertEquals(72.0, carried?.recovery)
    }

    // MARK: lastVitalsRow — the recovery-INDEPENDENT vitals carry (#543 follow-up). HRV / resting-HR /
    // respiratory exist without a recovery score, so this selector must carry the freshest STRICTLY-PRIOR
    // night that has ANY of them, NOT the freshest recovery-SCORED night. This is what keeps the overnight
    // HRV / Resting HR / Respiratory card in step with the (already-correct) per-field Key-Metrics tile
    // when a post-update re-analysis nulls last night's recovery while preserving its real vitals.

    @Test
    fun lastVitalsRow_keepsLastNightsOwnVitals_whenItsRecoveryWasNulled_documentsWholeRowBug() {
        // Post-update re-analysis nulled last night's RECOVERY but PRESERVED its real avgHrv/restingHr; an
        // older day was recovery-scored. The recovery-gated whole-row carry (lastScoredRecoveryDay) selects
        // that OLDER day, so a whole-row `carriedDay ?: day` read would discard last night's own 41 ms/61 bpm
        // — the tile-vs-card mismatch. The per-field read (today-first, else lastVitalsRow) keeps them.
        val days = listOf(
            recDay("2026-06-17", 65.0, hrv = 55.0, rhr = 50),   // older, recovery-scored
            recDay("2026-06-18", null, hrv = 41.0, rhr = 61),   // last night: recovery nulled, vitals intact
        )
        // The whole-row carry documents the bug: it picks the OLDER scored day, not last night.
        val scored = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-19",
            isToday = true, todayScored = false, isCalibrating = false,
            today = "2026-06-19",
        )
        assertEquals("2026-06-17", scored?.day)

        // The vitals carry keeps last night's OWN preserved values, so the per-field read is correct.
        val vitals = lastVitalsRow(days, todayKey = "2026-06-19")
        assertEquals("2026-06-18", vitals?.day)
        assertEquals(41.0, vitals?.avgHrv)
        assertEquals(61, vitals?.restingHr)

        // The per-field read the card now uses (today has no row yet → carry): last night's own vitals.
        val today: DailyMetric? = null
        assertEquals(41.0, today?.avgHrv ?: vitals?.avgHrv)
        assertEquals(61, today?.restingHr ?: vitals?.restingHr)
    }

    @Test
    fun lastVitalsRow_carriesVitals_evenWhenNoPriorNightWasEverRecoveryScored() {
        // Prior night has real vitals but its recovery is null, and today is empty. The recovery-gated
        // selector carries NOTHING (nothing was scored), yet the vitals must still carry so the card doesn't
        // blank while the tile shows a value.
        val days = listOf(
            recDay("2026-06-18", null, hrv = 44.0, rhr = 58),   // vitals present, recovery never scored
            recDay("2026-06-19", null),                          // today, empty
        )
        val scored = lastScoredRecoveryDay(
            days, selectedDayKey = "2026-06-19",
            isToday = true, todayScored = false, isCalibrating = false,
            today = "2026-06-19",
        )
        assertNull(scored)

        val vitals = lastVitalsRow(days, todayKey = "2026-06-19")
        assertEquals("2026-06-18", vitals?.day)
        assertEquals(44.0, vitals?.avgHrv)
        assertEquals(58, vitals?.restingHr)
    }

    @Test
    fun lastVitalsRow_neverCarriesAFutureDatedRow() {
        // Belt-and-suspenders (mirrors the #547 guard on lastScoredRecoveryDay): a bad-clock row dated far in
        // the future sits at the end of the oldest→newest list; the `day < todayKey` bound skips it so it can
        // never surface as "last night".
        val days = listOf(
            recDay("2026-06-18", 72.0, hrv = 50.0, rhr = 55),   // the real freshest prior vitals
            recDay("2999-01-01", null, hrv = 99.0, rhr = 99),   // future-dated pollution
        )
        val vitals = lastVitalsRow(days, todayKey = "2026-06-19")
        assertEquals("2026-06-18", vitals?.day)
        assertEquals(50.0, vitals?.avgHrv)
        assertEquals(55, vitals?.restingHr)
    }

    // Row builders for the six reads the Key-Metrics grid gained from the Your-cards dashboard.
    private fun sleepMinutesDay(day: String, totalSleepMin: Double?) =
        DailyMetric(deviceId = "my-whoop", day = day, totalSleepMin = totalSleepMin)

    private fun skinTempDay(day: String, devC: Double?) =
        DailyMetric(deviceId = "my-whoop", day = day, skinTempDevC = devC)

    // MARK: - One metric, one id, two surfaces (Key Metrics ⟷ Your cards)
    //
    // The two Today sections were independent registries over the SAME already-loaded values, so which
    // section a metric appeared in was an accident of history rather than a data limit. They now cover the
    // same metrics, and a metric's persisted id is shared, which is what lets a card find its score read.

    @Test
    fun everyMetricThatExistsOnBothSurfaces_sharesOnePersistedId() {
        // The raws are the join between the two registries (DashboardCard.scoreRead looks a card's tile up
        // by raw). If a future metric is added to one side under a different spelling, the join breaks
        // silently — a card would simply stop finding its read — so pin the overlap here.
        val tileRaws = KeyMetric.entries.map { it.raw }.toSet()
        val cardRaws = DashboardCard.entries.map { it.raw }.toSet()
        // Coupled is a navigation row, not a reading, so it has no tile twin. Everything else pairs up.
        assertEquals(cardRaws - setOf("coupled"), tileRaws)
        // And the pairing resolves in both directions.
        for (card in DashboardCard.entries.filter { it != DashboardCard.COUPLED }) {
            assertNotNull("no tile for card ${card.raw}", KeyMetric.fromRaw(card.raw))
        }
        for (metric in KeyMetric.entries) {
            assertNotNull("no card for tile ${metric.raw}", DashboardCard.fromRaw(metric.raw))
        }
    }

    @Test
    fun keyMetricRaws_areStableAndUnique() {
        // The raws are the persisted layout AND the backup/restore contract with the iOS enum. Renaming one
        // silently drops that tile from every saved layout, so pin the exact set.
        assertEquals(
            listOf(
                "charge", "effort", "rest", "hrv", "restingHr", "bloodOxygen", "respiratory",
                "steps", "weight", "calories", "sleep", "stress", "skinTemp", "vitality",
                "fitnessAge", "hydration",
            ),
            KeyMetric.entries.map { it.raw },
        )
        assertEquals(KeyMetric.entries.size, KeyMetric.entries.map { it.raw }.toSet().size)
    }

    @Test
    fun newMetricsAreOnByDefault_butASavedLayoutIsNeverOverwritten() {
        // On by default: an unset layout lists every tile, so the six that came over from the dashboard
        // show without a trip to the editor.
        assertEquals(KeyMetric.entries.toList(), KeyMetricPrefs.decodeEnabled(null))
        assertTrue(KeyMetric.SLEEP in KeyMetricPrefs.decodeEnabled(""))
        assertTrue(KeyMetric.STRESS in KeyMetricPrefs.decodeEnabled(null))
        // …but a user who already saved a layout keeps exactly what they chose — the new tiles do NOT
        // reappear in a customised grid.
        val saved = KeyMetricPrefs.decodeEnabled("hrv,steps")
        assertEquals(listOf(KeyMetric.HRV, KeyMetric.STEPS), saved)

        // Same contract on the dashboard: unset = every card that carries a value; Coupled stays opt-in.
        val defaults = DashboardCardPrefs.decodeEnabled(null)
        assertEquals(DashboardCard.entries.filter { it != DashboardCard.COUPLED }, defaults)
        assertFalse(DashboardCard.COUPLED in defaults)
        assertEquals(listOf(DashboardCard.SLEEP), DashboardCardPrefs.decodeEnabled("""["sleep"]"""))
    }

    // MARK: - Day-aware card subtitles

    @Test
    fun cardSubtitles_areUnchangedForToday() {
        // The dashboard now renders for any selected day, but today must read exactly as it always has.
        for (card in DashboardCard.entries) {
            assertEquals(card.subtitle, dashboardCardSubtitle(card, isToday = true))
        }
    }

    @Test
    fun cardSubtitles_stopSayingTodayOnAPastDay() {
        // The three today-worded subtitles would misdate a row three weeks back.
        assertEquals("That day", dashboardCardSubtitle(DashboardCard.STEPS, isToday = false))
        assertEquals("That night", dashboardCardSubtitle(DashboardCard.SLEEP, isToday = false))
        assertEquals("That day's fluid", dashboardCardSubtitle(DashboardCard.HYDRATION, isToday = false))
    }

    @Test
    fun cardSubtitles_dayNeutralOnesPassThroughUnchanged() {
        // Everything else was already day-neutral; a past day must not reword it.
        val dayNeutral = DashboardCard.entries -
            setOf(DashboardCard.STEPS, DashboardCard.SLEEP, DashboardCard.HYDRATION)
        for (card in dayNeutral) {
            assertEquals(card.subtitle, dashboardCardSubtitle(card, isToday = false))
        }
    }

    @Test
    fun noCardSubtitle_saysTodayOrLastNightOnAPastDay() {
        // The rule that matters, stated once: after the day gate was lifted, no subtitle on a scrolled-back
        // day may claim to describe today. Catches a future card added with today-worded copy.
        for (card in DashboardCard.entries) {
            val s = dashboardCardSubtitle(card, isToday = false).lowercase()
            assertFalse("'${card.raw}' still says today: $s", s.contains("today"))
            assertFalse("'${card.raw}' still says last night: $s", s.contains("last night"))
        }
    }

    // MARK: - The four score reads shared by tile and card

    @Test
    fun chargeRead_prefersTheDaysOwnScore_thenCalibration_thenTheCarriedValue() {
        val scored = chargeRead(recDay("2026-06-19", 71.0), recoveryCalibration = null, lastScoredCharge = null)
        assertEquals("71", scored.value)
        assertEquals("%", scored.unit)
        assertEquals(0.71, scored.frac!!, 1e-9)

        // Mid-calibration the tile shows "n/N" — and must NOT append a percent sign to a fraction.
        val calibrating = chargeRead(recDay("2026-06-19", null), recoveryCalibration = 3, lastScoredCharge = null)
        assertEquals("", calibrating.unit)
        assertTrue(calibrating.value.startsWith("3/"))
        assertNull(calibrating.frac)

        val carried = chargeRead(
            recDay("2026-06-19", null),
            recoveryCalibration = null,
            lastScoredCharge = LastCharge(value = 64.0, caption = "18 Jun"),
        )
        assertEquals("64", carried.value)
        assertEquals("%", carried.unit)
    }

    @Test
    fun chargeRead_withNothingToShow_isNoDataAndAnEmptyVessel() {
        val none = chargeRead(null, recoveryCalibration = null, lastScoredCharge = null)
        assertEquals("No Data", none.value)
        assertEquals("", none.unit)
        assertNull(none.frac)
    }

    @Test
    fun restRead_scoresAndFillsTogether_orReadsNoData() {
        val rested = restRead(82.0)
        assertEquals("82", rested.value)
        assertEquals("%", rested.unit)
        assertEquals(0.82, rested.frac!!, 1e-9)

        val none = restRead(null)
        assertEquals("No Data", none.value)
        assertEquals("", none.unit)
        assertNull(none.frac)
    }

    @Test
    fun weightRead_hasNoVesselFill_andFollowsTheUnitToggle() {
        // A body weight has no goal to fill toward, so the vessel stays empty rather than half-full.
        val metric = weightRead(74.5, profileWeightKg = 90.0, unitSystem = UnitSystem.METRIC)
        assertNull(metric.frac)
        assertEquals("", metric.unit)
        assertEquals(weightTile(74.5, 90.0, UnitSystem.METRIC).value, metric.value)

        val imperial = weightRead(74.5, profileWeightKg = 90.0, unitSystem = UnitSystem.IMPERIAL)
        assertEquals(weightTile(74.5, 90.0, UnitSystem.IMPERIAL).value, imperial.value)
    }

    @Test
    fun scoreReads_areLookedUpByASharedRaw_soACardFindsItsTile() {
        // The mechanism the Your-cards rows use to render the SAME read the tile renders.
        val reads = mapOf(
            KeyMetric.CHARGE to chargeRead(recDay("2026-06-19", 71.0), null, null),
            KeyMetric.REST to restRead(82.0),
        )
        assertEquals("71", DashboardCard.CHARGE.scoreRead(reads)?.value)
        assertEquals("82", DashboardCard.REST.scoreRead(reads)?.value)
        // A card with no score read (a vital, or a score the caller didn't resolve) falls through to null so
        // the row keeps its own resolution instead of silently blanking.
        assertNull(DashboardCard.HRV.scoreRead(reads))
        assertNull(DashboardCard.EFFORT.scoreRead(reads))
    }

    // MARK: - The six reads the grid gained, shared with the card rows

    @Test
    fun sleepRead_formatsHoursAndMinutes_andFillsAgainstAnEightHourNight() {
        val mv = MetricReads.sleep(sleepMinutesDay("2026-06-19", 432.0), null)
        assertEquals("7h 12m", mv.number)
        assertEquals("", mv.unit)
        assertEquals(0.9, mv.frac!!, 1e-9)
        // A long night fills the vessel without overflowing it.
        assertEquals(1.0, MetricReads.sleep(sleepMinutesDay("2026-06-19", 600.0), null).frac!!, 1e-9)
        assertNull(MetricReads.sleep(null, null).number)
    }

    @Test
    fun sleepRead_fallsBackToTheCarriedNight() {
        // Right after the logical-day rollover today's row carries no sleep yet; the tile and the card both
        // read the carried night rather than blanking.
        val mv = MetricReads.sleep(sleepMinutesDay("2026-06-19", null), sleepMinutesDay("2026-06-18", 420.0))
        assertEquals("7h 0m", mv.number)
    }

    @Test
    fun skinTempRead_isSignedSoADropReadsHonestly() {
        assertEquals("+0.3°", MetricReads.skinTemp(skinTempDay("2026-06-19", 0.3), null).number)
        assertEquals("-0.4°", MetricReads.skinTemp(skinTempDay("2026-06-19", -0.4), null).number)
        // A deviation has no natural ceiling, so there is no vessel to fill.
        assertNull(MetricReads.skinTemp(skinTempDay("2026-06-19", 0.3), null).frac)
        assertNull(MetricReads.skinTemp(null, null).number)
    }

    @Test
    fun stressRead_roundsAndFillsAgainstThree_andNullMeansCalibrating() {
        val mv = MetricReads.stress(2.4)
        assertEquals("2", mv.number)
        assertEquals(0.8, mv.frac!!, 1e-9)
        // Null is NOT "no data" — Stress is baseline-relative and the caller substitutes "Calibrating".
        assertNull(MetricReads.stress(null).number)
        assertNull(MetricReads.stress(null).frac)
    }

    @Test
    fun vitalityAndFitnessAgeReads_roundToWholeUnits() {
        assertEquals("87", MetricReads.vitality(86.6).number)
        assertEquals(0.87, MetricReads.vitality(87.0).frac!!, 1e-9)

        val fa = MetricReads.fitnessAge(31.4)
        assertEquals("31", fa.number)
        assertEquals("yrs", fa.unit)
        // Fixed half-fill: younger is better, so a ceiling would read backwards.
        assertEquals(0.5, fa.frac!!, 1e-9)
        assertNull(MetricReads.fitnessAge(null).frac)
    }

    @Test
    fun hydrationRead_alwaysResolves_becauseTheGoalIsAlwaysDerivable() {
        assertEquals("1.2 / 3.2 L", MetricReads.hydration(1200.0, 3200).number)
        // A fresh day reads zero rather than "No Data".
        assertEquals("0.0 / 3.2 L", MetricReads.hydration(0.0, 3200).number)
        assertNull(MetricReads.hydration(1200.0, 3200).frac)
    }

    // MARK: - Tile navigation matches its card row

    @Test
    fun everyTileWithACardDestination_landsOnTheSameScreen() {
        // A tile and its row must not disagree about where the metric lives.
        for (metric in KeyMetric.entries) {
            val card = DashboardCard.fromRaw(metric.raw) ?: continue
            assertEquals(
                "tile ${metric.raw} and its card open different details",
                dashboardCardMetricKey(card),
                keyMetricDetailKey(metric),
            )
        }
    }

    @Test
    fun scoreTilesAndScoreCards_bothHaveNoMetricDetail() {
        // Charge / Effort / Rest reach their explainer through the hero ring's ⓘ and Weight has no trend
        // page, so neither surface may promise one (the card row drops its chevron on the strength of this).
        for (m in listOf(KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST, KeyMetric.WEIGHT)) {
            assertNull(keyMetricDetailKey(m))
            assertNull(dashboardCardMetricKey(DashboardCard.fromRaw(m.raw)!!))
        }
    }

    // MARK: - A past day shows THAT day's values, never today's
    //
    // Five reads were "the newest value anywhere in history". That was indistinguishable from today's while
    // the cards only rendered at offset 0 — but the dashboard now follows the day selector, so an unbounded
    // read would stamp today's number onto a day weeks back. Each is bounded by the selected day below.

    @Test
    fun weight_asOfADay_ignoresLaterWeighIns() {
        val apple = listOf(
            appleDay("2026-06-10", 80.0),
            appleDay("2026-06-17", 78.0),
            appleDay("2026-06-19", 74.5),   // today's weigh-in
        )
        // Scrolled back to the 17th: that day's weight, not this morning's.
        assertEquals(78.0, latestWeightKg(apple, emptyList(), asOfDay = "2026-06-17"))
        assertEquals(80.0, latestWeightKg(apple, emptyList(), asOfDay = "2026-06-16"))
        assertEquals(74.5, latestWeightKg(apple, emptyList(), asOfDay = "2026-06-19"))
    }

    @Test
    fun weight_asOfADay_carriesTheLastReadingBeforeIt_notNothing() {
        // Weight isn't logged daily, so a day with no weigh-in of its own honestly shows the most recent
        // one that existed by then — the reading you'd have seen standing on that day.
        val apple = listOf(appleDay("2026-06-10", 80.0), appleDay("2026-06-19", 74.5))
        assertEquals(80.0, latestWeightKg(apple, emptyList(), asOfDay = "2026-06-15"))
        // …and nothing at all before the first ever reading.
        assertNull(latestWeightKg(apple, emptyList(), asOfDay = "2026-06-01"))
    }

    @Test
    fun weight_unboundedReadIsUnchanged_forCallersThatWantNewestEver() {
        val apple = listOf(appleDay("2026-06-10", 80.0), appleDay("2026-06-19", 74.5))
        assertEquals(74.5, latestWeightKg(apple, emptyList()))
    }

    @Test
    fun activeKcal_isTheDaysOwnFigure_notTheNewestAnywhere() {
        val apple = listOf(
            AppleDaily(deviceId = "apple-health", day = "2026-06-17", activeKcal = 410.0),
            AppleDaily(deviceId = "apple-health", day = "2026-06-19", activeKcal = 730.0),
        )
        assertEquals(410.0, activeKcalForDay(apple, emptyList(), "2026-06-17"))
        assertEquals(730.0, activeKcalForDay(apple, emptyList(), "2026-06-19"))
        // A day with no imported energy borrows nothing — the tile reads No Data instead of another day's.
        assertNull(activeKcalForDay(apple, emptyList(), "2026-06-18"))
    }

    @Test
    fun activeKcal_unionsBothSources_takesTheLargerForTheDay() {
        // Same rule as stepsForDay: a day present in both sources is one day counted twice, not two days.
        val apple = listOf(AppleDaily(deviceId = "apple-health", day = "2026-06-19", activeKcal = 500.0))
        val hc = listOf(AppleDaily(deviceId = "health-connect", day = "2026-06-19", activeKcal = 730.0))
        assertEquals(730.0, activeKcalForDay(apple, hc, "2026-06-19"))
    }

    @Test
    fun weeklyScores_readAsOfTheDay_neverAValueComputedLater() {
        // Fitness Age / Vitality are computed weekly. A day may only show a score that already existed by
        // then — showing a later one would give that day a reading it could not have had.
        val series = listOf("2026-06-01" to 34.0, "2026-06-08" to 33.0, "2026-06-15" to 31.0)
        assertEquals(33.0, valueAsOfDay(series, "2026-06-10"))
        assertEquals(33.0, valueAsOfDay(series, "2026-06-14"))
        assertEquals(31.0, valueAsOfDay(series, "2026-06-15"))
        assertEquals(31.0, valueAsOfDay(series, "2026-06-19"))
        // Before the first score there is nothing to show.
        assertNull(valueAsOfDay(series, "2026-05-31"))
    }

    @Test
    fun weeklyScores_asOfIsIndependentOfSeriesOrder() {
        // The series arrives merged across computed lineages (identity fusion), so it is not sorted.
        val jumbled = listOf("2026-06-15" to 31.0, "2026-06-01" to 34.0, "2026-06-08" to 33.0)
        assertEquals(33.0, valueAsOfDay(jumbled, "2026-06-10"))
        assertNull(valueAsOfDay(emptyList(), "2026-06-10"))
    }

    @Test
    fun stressPlaceholder_onlyTodayCalibrates() {
        // "Calibrating" says the baseline is still seeding — true today, a lie on a day weeks back, where
        // the score simply doesn't exist and never will. Both surfaces apply the same rule.
        assertEquals(
            "Calibrating",
            dashboardCardValue(
                card = DashboardCard.STRESS, day = null, carriedDay = null, vitalsDay = null,
                stress = null, fitnessAge = null, vitality = null,
                importedStepsForDay = null, estimatedStepsForDay = null, latestActiveKcal = null,
                hydrationTotalMl = 0.0, hydrationGoalMl = 0, isToday = true,
            ),
        )
        assertEquals(
            "No Data",
            dashboardCardValue(
                card = DashboardCard.STRESS, day = null, carriedDay = null, vitalsDay = null,
                stress = null, fitnessAge = null, vitality = null,
                importedStepsForDay = null, estimatedStepsForDay = null, latestActiveKcal = null,
                hydrationTotalMl = 0.0, hydrationGoalMl = 0, isToday = false,
            ),
        )
        // A real score reads the same on either day.
        assertEquals(
            "2",
            dashboardCardValue(
                card = DashboardCard.STRESS, day = null, carriedDay = null, vitalsDay = null,
                stress = 2.0, fitnessAge = null, vitality = null,
                importedStepsForDay = null, estimatedStepsForDay = null, latestActiveKcal = null,
                hydrationTotalMl = 0.0, hydrationGoalMl = 0, isToday = false,
            ),
        )
    }

    // MARK: - The Recovery-vitals caption names the day on screen

    @Test
    fun vitalsCaption_namesTheDayOnScreen_onEveryDayIncludingToday() {
        // ONE rule, no special cases. Sleep is banked by wake day, so the row dated D holds that night and
        // D is the date every other label uses for it — the day selector, the Key Metrics header, Trends.
        assertEquals("Overnight · 13 Aug", heroVitalsNightLine("2026-08-13"))
        assertEquals("Overnight · 12 Aug", heroVitalsNightLine("2026-08-12"))
        assertEquals("Overnight · 11 Aug", heroVitalsNightLine("2026-08-11"))
        assertEquals("Overnight · 24 Jul", heroVitalsNightLine("2026-07-24"))
    }

    @Test
    fun vitalsCaption_isUniqueAcrossConsecutiveDays() {
        // THE regression this pins. Today used to keep a D-1 special case ("last night names a night"),
        // which made today's caption collide with yesterday's screen: today (13 Aug, holding last night's
        // sleep) read "Last night · 12 Aug" while the real 12 Aug screen read "Overnight · 12 Aug" with
        // DIFFERENT numbers. One date, two readings — the 12th appeared twice and every other day once.
        // Any caption rule that dates one day by another day's date fails here.
        val days = listOf(
            "2026-08-08", "2026-08-09", "2026-08-10",
            "2026-08-11", "2026-08-12", "2026-08-13",
        )
        val captions = days.map { heroVitalsNightLine(it) }
        assertEquals("a caption is reused across days: $captions", days.size, captions.toSet().size)
    }

    @Test
    fun vitalsCaption_agreesWithTheCarryCaption_forTheSameRow() {
        // Both captions can appear in this one card. carriedCaption dates a carried row by its OWN day, so
        // the non-carry branch must not date the same row differently — that mismatch was the first bug.
        val row = "2026-08-11"
        assertTrue(carriedCaption(row, today = "2026-08-12").endsWith("11 Aug"))
        assertTrue(heroVitalsNightLine(row).endsWith("11 Aug"))
    }

    @Test
    fun vitalsCaption_neverClaimsANightRelativeToNow() {
        // "Last night" is a claim about the wall clock, and this caption belongs to the day on screen. The
        // only caption allowed to say it is carriedCaption, which is deliberately pointing at ANOTHER day.
        for (key in listOf("2026-07-24", "2026-08-01", "2026-08-11", "2026-08-13")) {
            assertFalse(heroVitalsNightLine(key).contains("Last night"))
            assertFalse(heroVitalsNightLine(key).contains("Yesterday"))
        }
    }

    @Test
    fun vitalsCaption_neverShiftsTheDate_acrossMonthAndYearBoundaries() {
        // No arithmetic on the date at all now, so a boundary is not a special case either.
        assertEquals("Overnight · 1 Jan", heroVitalsNightLine("2027-01-01"))
        assertEquals("Overnight · 1 Mar", heroVitalsNightLine("2026-03-01"))
        assertEquals("Overnight · 31 Dec", heroVitalsNightLine("2026-12-31"))
    }

    @Test
    fun vitalsCaption_matchesTheKeyMetricsHeaderDate_forTheSameDay() {
        // The two sit inches apart showing the SAME numbers, so they must carry the same date. This is the
        // contradiction a user can see on one screen without scrolling.
        for (key in listOf("2026-08-13", "2026-08-11", "2026-07-24")) {
            val headerDate = java.time.LocalDate.parse(key)
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US))
            assertTrue(heroVitalsNightLine(key).endsWith(headerDate))
        }
    }

    @Test
    fun vitalsCaption_unparseableKeyStillRendersACaption() {
        // Never blank: a malformed key falls back to the wall clock rather than dropping the line.
        assertTrue(heroVitalsNightLine("not-a-date").startsWith("Overnight · "))
        assertTrue(heroVitalsNightLine("").startsWith("Overnight · "))
    }
}
