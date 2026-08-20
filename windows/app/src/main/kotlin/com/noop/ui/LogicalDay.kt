package com.noop.ui

import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The "logical day" key the dashboard treats as Today.
 *
 * A naive `LocalDate.now()` rolls the moment the clock passes midnight, so between 00:00 and the
 * morning the dashboard would look up a brand-new calendar day that has no banked row yet and blank
 * out — even though the user is still in the same wear/sleep cycle as the previous evening.
 *
 * The logical day rolls at [rolloverHour] (04:00 LOCAL) instead: it is the calendar date of
 * `now - rolloverHour hours`, so the small hours after midnight still resolve to the PRIOR calendar
 * date's row. This is a PRESENTATION-layer remap only. Pure + injectable so the boundaries are
 * testable without a live clock.
 */
internal fun logicalDay(
    now: ZonedDateTime,
    rolloverHour: Int = LOGICAL_DAY_ROLLOVER_HOUR,
): LocalDate = now.minusHours(rolloverHour.toLong()).toLocalDate()

/** Convenience overload for the live call sites: the logical day for the current instant in [zone]. */
internal fun logicalDayNow(
    zone: ZoneId = ZoneId.systemDefault(),
    rolloverHour: Int = LOGICAL_DAY_ROLLOVER_HOUR,
): LocalDate = logicalDay(ZonedDateTime.now(zone), rolloverHour)

/** ISO `yyyy-MM-dd` key for the current logical day — matches how [DailyMetric.day] is stored. */
internal fun logicalDayKeyNow(
    zone: ZoneId = ZoneId.systemDefault(),
    rolloverHour: Int = LOGICAL_DAY_ROLLOVER_HOUR,
): String = logicalDayNow(zone, rolloverHour).toString()

/**
 * Start-of-logical-day as an epoch second in [zone] — the anchor for the Today HR-trend window so it
 * spans from the logical day's 00:00 rather than restarting at the new calendar midnight while we're
 * still showing yesterday's logical day in the small hours.
 */
internal fun logicalDayStartEpochSecond(
    now: ZonedDateTime,
    zone: ZoneId = now.zone,
    rolloverHour: Int = LOGICAL_DAY_ROLLOVER_HOUR,
): Long = logicalDay(now, rolloverHour).atStartOfDay(zone).toEpochSecond()

/**
 * Pure resolver behind the dashboard's "today" row: prefer the LOCAL-calendar-day row when it differs
 * from the logical day AND has a banked night, else fall back to the logical-day row (preserving the
 * anti-blank guard). [localKey] == [logicalKey] (the common daytime case) collapses to the plain
 * logical lookup.
 */
internal fun resolveTodayRow(days: List<DailyMetric>, logicalKey: String, localKey: String): DailyMetric? {
    if (localKey != logicalKey) {
        days.lastOrNull { it.day == localKey && it.totalSleepMin != null }?.let { return it }
    }
    return days.lastOrNull { it.day == logicalKey }
}

/**
 * The SINGLE anchor the home-screen widget push resolves the row it describes through. Pure +
 * testable without a live clock.
 */
internal fun widgetAnchorRow(days: List<DailyMetric>, logicalKey: String, localKey: String): DailyMetric? {
    val todayRow = resolveTodayRow(days, logicalKey, localKey)
    if (todayRow?.recovery != null) return todayRow
    val anchorKey = todayRow?.day ?: logicalKey
    return days.lastOrNull { it.recovery != null && it.day < anchorKey }
}

/**
 * The freshest STRICTLY-PRIOR row that carries a real overnight VITAL (HRV / resting-HR / respiratory),
 * regardless of whether that night was recovery-scored. Pure + testable; days is oldest->newest.
 */
internal fun lastVitalsRow(days: List<DailyMetric>, todayKey: String): DailyMetric? =
    days.lastOrNull { (it.avgHrv != null || it.restingHr != null || it.respRateBpm != null) && it.day < todayKey }

/** 04:00 local — the hour the logical day rolls. Between midnight and this hour, Today stays put. */
internal const val LOGICAL_DAY_ROLLOVER_HOUR: Int = 4

/** Exposed for symmetry / call-site readability (start of the rollover window). */
internal val LOGICAL_DAY_ROLLOVER_TIME: LocalTime = LocalTime.of(LOGICAL_DAY_ROLLOVER_HOUR, 0)
