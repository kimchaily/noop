package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyMetric
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Trends
//
// Desktop port of the Android TrendsScreen. Shows the longitudinal view:
// range control (W/M/3M/6M/1Y/ALL), hero Recovery chart, small-multiple
// HRV / RHR / Effort charts, recovery history bar strip, and a week-in-review
// digest with prev/next week navigation.
//
// Skips the Android liquid UI effects (LiquidScreenSky, LiquidVessel,
// staggeredAppear, liquidPress) which are mobile-only visual polish; all
// data logic and chart rendering is identical.

@Composable
fun TrendsScreen(viewModel: DesktopAppViewModel) {
    val reactiveDays by viewModel.recentDays.collectAsState()

    var fullHistory by remember { mutableStateOf<List<DailyMetric>?>(null) }
    LaunchedEffect(Unit) {
        fullHistory = viewModel.repository.daysMerged(viewModel.activeStrapId)
    }
    val days = fullHistory ?: reactiveDays

    val effortScale = UnitPrefs.effortScale()

    var range by remember { mutableStateOf(TrendsRange.Quarter) }

    var weekOffset by remember { mutableStateOf(0) }
    val minWeekOffset = remember(days) { minWeekOffset(days) }
    LaunchedEffect(minWeekOffset) { weekOffset = weekOffset.coerceIn(minWeekOffset, 0) }

    val recovery = remember(days, range) { resolveMetric(days, range) { it.recovery } }
    val hrv = remember(days, range) { resolveMetric(days, range) { it.avgHrv } }
    val rhr = remember(days, range) { resolveMetric(days, range) { it.restingHr?.toDouble() } }
    val strain = remember(days, range) { resolveMetric(days, range) { it.strain } }

    var sleepPerfByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(days) {
        sleepPerfByDay = runCatching {
            viewModel.repository.resolvedSeries("sleep_performance", "my-whoop", "0000-00-00", "9999-99-99")
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
    }
    val rest = remember(days, range, sleepPerfByDay) {
        resolveMetric(days, range) { d -> sleepPerfByDay[d.day] }
    }
    val recAvg = recovery.values.averageOrNull()

    LazyScreenScaffold(
        title = "Trends",
        subtitle = "The thread of you over time.",
    ) {
        if (days.isEmpty()) {
            item { EmptyTrends() }
            return@LazyScreenScaffold
        }

        item {
            WeeklyDigestNav(
                days = days,
                weekOffset = weekOffset,
                minWeekOffset = minWeekOffset,
                onStep = { delta -> weekOffset = (weekOffset + delta).coerceIn(minWeekOffset, 0) },
                effortScale = effortScale,
            )
        }

        item {
            WeekInReviewCard(
                charge = recovery,
                effort = strain,
                rest = rest,
                effortScale = effortScale,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentedPillControl(
                        items = TrendsRange.entries.toList(),
                        selection = range,
                        label = { it.label },
                        onSelect = { range = it },
                    )
                    Spacer(Modifier.weight(1f))
                    Overline(range.subtitle, color = Palette.textTertiary)
                }
                Text(
                    recovery.caption,
                    style = NoopType.footnote,
                    color = if (recovery.widened) Palette.statusWarning else Palette.textTertiary,
                )
            }
        }

        item {
            ChartCard(
                title = "Charge",
                subtitle = range.subtitle,
                trailing = recAvg?.let { "${it.roundToInt()}" },
                color = Palette.chargeColor,
                tipColor = Palette.chargeBright,
                tint = Palette.chargeColor,
                values = recovery.values,
                dates = recovery.dates,
                formatY = { "${it.roundToInt()}" },
                change = periodChange(recovery.values),
                higherIsBetter = true,
                changeFmt = { "${it.roundToInt()}" },
                chartHeadroom = 0.06f,
                footer = listOf(
                    "Avg" to (recAvg?.let { "${it.roundToInt()}" } ?: EM_DASH),
                    "Peak" to (recovery.values.maxOrNull()?.let { "${it.roundToInt()}" } ?: EM_DASH),
                    "Low" to (recovery.values.minOrNull()?.let { "${it.roundToInt()}" } ?: EM_DASH),
                    "Days" to "${recovery.values.size}",
                ),
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                SectionHeader("Daily signals", overline = "Trends")
                MetricTrendCard(
                    title = "Heart rate variability", unit = "ms",
                    color = Palette.metricPurple,
                    tint = Palette.chargeColor,
                    higherIsBetter = true,
                    resolved = hrv,
                    fmt = { "${it.roundToInt()}" },
                )
                MetricTrendCard(
                    title = "Resting heart rate", unit = "bpm",
                    color = Palette.metricRose,
                    tint = Palette.chargeColor,
                    higherIsBetter = false,
                    resolved = rhr,
                    fmt = { "${it.roundToInt()}" },
                )
                MetricTrendCard(
                    title = "Effort", unit = "/ ${UnitFormatter.effortScaleMax(effortScale)}",
                    color = Palette.effortColor,
                    tint = Palette.effortColor,
                    tipColor = Palette.effortBright,
                    higherIsBetter = null,
                    resolved = strain,
                    fmt = { UnitFormatter.effortDisplay(it, effortScale) },
                )
            }
        }

        item {
            RecoveryHistoryCard(days = days, range = range)
        }
    }
}

// MARK: - Week-in-review digest with prev/next week browsing

private fun minWeekOffset(days: List<DailyMetric>): Int {
    val earliest = days.firstOrNull()?.day ?: return 0
    val earliestMon = WeeklyDigestEngine.mondayOfWeek(earliest) ?: return 0
    val thisMon = WeeklyDigestEngine.mondayOfWeek(logicalDayKeyNow()) ?: return 0
    var off = 0
    var mon = thisMon
    while (mon > earliestMon && off > -520) {
        mon = WeeklyDigestEngine.addDays(mon, -7)
        off -= 1
    }
    return off
}

@Composable
private fun WeeklyDigestNav(
    days: List<DailyMetric>,
    weekOffset: Int,
    minWeekOffset: Int,
    onStep: (Int) -> Unit,
    effortScale: EffortScale,
) {
    if (days.isEmpty()) return
    val anchorDay = remember(weekOffset) {
        WeeklyDigestEngine.addDays(logicalDayKeyNow(), weekOffset * 7)
    }
    val factor = effortDisplayFactor(effortScale)
    val digest = remember(days, anchorDay, factor) {
        buildWeeklyDigest(days, anchorDay, factor)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WeekNavBar(weekOffset = weekOffset, minWeekOffset = minWeekOffset, onStep = onStep)
        if (digest.isEmpty) {
            DataPendingNote(
                title = "No readings this week",
                body = "Step to another week with the arrows above to see its review.",
            )
        } else {
            NoopCard { WeeklyDigestContent(digest = digest, compact = true) }
        }
    }
}

@Composable
private fun WeekNavBar(weekOffset: Int, minWeekOffset: Int, onStep: (Int) -> Unit) {
    val atOldest = weekOffset <= minWeekOffset
    val atNewest = weekOffset >= 0
    val label = when {
        weekOffset == 0 -> "This week"
        weekOffset == -1 -> "Last week"
        else -> "${-weekOffset} weeks ago"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onStep(-1) },
            enabled = !atOldest,
        ) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = "Previous week",
                tint = if (atOldest) Palette.textTertiary else Palette.accent,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = NoopType.headline, color = Palette.textPrimary)
            Overline("Week in review", color = Palette.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onStep(1) },
            enabled = !atNewest,
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Next week",
                tint = if (atNewest) Palette.textTertiary else Palette.accent,
            )
        }
    }
}

// MARK: - Week in review card

@Composable
private fun WeekInReviewCard(
    charge: ResolvedMetric,
    effort: ResolvedMetric,
    rest: ResolvedMetric,
    effortScale: EffortScale,
    modifier: Modifier = Modifier,
) {
    val chargeAvg = charge.values.averageOrNull()
    val effortAvg = effort.values.averageOrNull()
    val restAvg = rest.values.averageOrNull()
    if (chargeAvg == null && effortAvg == null && restAvg == null) return

    NoopCard(modifier = modifier, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Week in review", overline = "Charge / Effort / Rest")
            if (chargeAvg != null) {
                PipScoreRow(
                    label = "Charge", value = chargeAvg, range = 0f..100f,
                    tint = Palette.chargeColor, format = { "${it.roundToInt()}" },
                )
            }
            if (effortAvg != null) {
                val display = UnitFormatter.effortValue(effortAvg, effortScale)
                val maxV = UnitFormatter.effortValue(100.0, effortScale)
                val oneDecimal = effortScale == EffortScale.WHOOP
                PipScoreRow(
                    label = "Effort", value = display, range = 0f..maxV.toFloat(),
                    tint = Palette.effortColor,
                    format = { if (oneDecimal) String.format(Locale.US, "%.1f", it) else "${it.roundToInt()}" },
                )
            }
            if (restAvg != null) {
                PipScoreRow(
                    label = "Rest", value = restAvg, range = 0f..100f,
                    tint = Palette.restColor, format = { "${it.roundToInt()}" },
                )
            }
        }
    }
}

@Composable
private fun PipScoreRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    tint: Color,
    format: (Double) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(
            text = label.uppercase(),
            style = NoopType.overline,
            color = Palette.textSecondary,
        )
        CountUpText(
            value = value,
            format = format,
            style = NoopType.number(30f, weight = FontWeight.Bold),
            color = Palette.textPrimary,
        )
        PipBar(value = value.toFloat(), range = range, tint = tint)
    }
}

/** A simple segmented progress bar — the desktop stand-in for the Android PipBar. */
@Composable
private fun PipBar(value: Float, range: ClosedFloatingPointRange<Float>, tint: Color) {
    val minV = range.start
    val maxV = range.endInclusive
    val span = (maxV - minV).takeIf { it > 0f } ?: 1f
    val fraction = ((value - minV) / span).coerceIn(0f, 1f)
    val segments = 20
    val filled = (fraction * segments).roundToInt()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(Palette.surfaceInset),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        for (i in 0 until segments) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(shape)
                    .background(if (i < filled) tint else Color.Transparent),
            )
        }
    }
}

// MARK: - buildWeeklyDigest wrapper

/** Build a [WeeklyDigest] from a list of [DailyMetric] days, anchored on the Monday of [anchorDay]'s week. */
internal fun buildWeeklyDigest(
    days: List<DailyMetric>,
    anchorDay: String,
    effortDisplayFactor: Double = 1.0,
): WeeklyDigest {
    val charge = days.mapNotNull { d -> d.recovery?.let { d.day to it } }.toMap()
    val effort = days.mapNotNull { d -> d.strain?.let { d.day to it } }.toMap()
    val rest = days.mapNotNull { d -> d.totalSleepMin?.let { d.day to (it / 480.0 * 100.0).coerceIn(0.0, 100.0) } }.toMap()
    val rhr = days.mapNotNull { d -> d.restingHr?.let { d.day to it.toDouble() } }.toMap()
    val hrv = days.mapNotNull { d -> d.avgHrv?.let { d.day to it } }.toMap()

    val byMetric = mapOf(
        WeeklyMetric.CHARGE to charge,
        WeeklyMetric.EFFORT to effort,
        WeeklyMetric.REST to rest,
        WeeklyMetric.RHR to rhr,
        WeeklyMetric.HRV to hrv,
    )
    return WeeklyDigestEngine.build(byMetric, anchorDay, effortDisplayFactor)
}

/** Effort display factor: 1.0 for 0-100 scale, 0.21 for WHOOP 0-21 scale. */
internal fun effortDisplayFactor(scale: EffortScale): Double =
    if (scale == EffortScale.WHOOP) UnitFormatter.EFFORT_SCALE_FACTOR else 1.0

// MARK: - WeeklyDigestContent (compact rendering)

@Composable
private fun WeeklyDigestContent(digest: WeeklyDigest, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            digest.metrics.forEach { summary ->
                if (summary.thisWeek.n > 0) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Overline(summary.metric.label, color = Palette.textTertiary)
                        Text(
                            "${summary.thisWeek.mean.roundToInt()}${summary.metric.unit}",
                            style = NoopType.bodyNumber,
                            color = Palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary.weekOverWeek.pctChange != null) {
                            val pct = summary.weekOverWeek.pctChange
                            val sign = if (pct >= 0) "+" else ""
                            val color = when (summary.wowGoodness) {
                                1 -> Palette.statusPositive
                                -1 -> Palette.metricRose
                                else -> Palette.textTertiary
                            }
                            Text(
                                "$sign${pct.roundToInt()}%",
                                style = NoopType.footnote,
                                color = color,
                            )
                        }
                    }
                }
            }
        }
        if (digest.focalPoints.isNotEmpty()) {
            HorizontalDivider(color = Palette.hairline)
            digest.focalPoints.forEach { line ->
                Text(
                    line,
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        }
    }
}

// MARK: - Range control model

private enum class TrendsRange(val days: Int?, val label: String, val longName: String) {
    Week(7, "W", "week"),
    Month(30, "M", "month"),
    Quarter(90, "3M", "3 months"),
    Half(180, "6M", "6 months"),
    Year(365, "1Y", "year"),
    All(null, "ALL", "all history");

    val subtitle: String get() = days?.let { "Trailing $it days" } ?: "All history"

    val widening: List<TrendsRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - Resolved metric

private data class ResolvedMetric(
    val values: List<Double>,
    val dates: List<String>,
    val effective: TrendsRange,
    val widened: Boolean,
    val caption: String,
)

private fun resolveMetric(
    days: List<DailyMetric>,
    selected: TrendsRange,
    value: (DailyMetric) -> Double?,
): ResolvedMetric {
    for (r in selected.widening) {
        val pts = windowPoints(days, r, value)
        if (pts.isNotEmpty()) {
            return ResolvedMetric(
                values = pts.map { it.second },
                dates = pts.map { it.first },
                effective = r,
                widened = r != selected,
                caption = caption(pts.size, r, selected),
            )
        }
    }
    val pts = windowPoints(days, TrendsRange.All, value)
    return ResolvedMetric(
        values = pts.map { it.second },
        dates = pts.map { it.first },
        effective = TrendsRange.All,
        widened = TrendsRange.All != selected,
        caption = caption(pts.size, TrendsRange.All, selected),
    )
}

private fun windowPoints(
    days: List<DailyMetric>,
    range: TrendsRange,
    value: (DailyMetric) -> Double?,
): List<Pair<String, Double>> {
    if (days.isEmpty()) return emptyList()
    val sliced = when (val n = range.days) {
        null -> days
        else -> {
            val cutoff = LocalDate.now().minusDays((n - 1).toLong()).toString()
            days.filter { it.day >= cutoff }
        }
    }
    return sliced.mapNotNull { d -> value(d)?.let { d.day to it } }
}

private fun caption(count: Int, eff: TrendsRange, selected: TrendsRange): String {
    val unit = if (count == 1) "reading" else "readings"
    return if (eff != selected) {
        "$count $unit / sparse, widened to ${eff.longName}"
    } else {
        "$count $unit / ${selected.longName}"
    }
}

// MARK: - ChartCard

@Composable
private fun ChartCard(
    title: String,
    subtitle: String?,
    trailing: String?,
    color: Color,
    values: List<Double>,
    footer: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    dates: List<String> = emptyList(),
    formatY: (Double) -> String = { "${it.roundToInt()}" },
    tint: Color? = null,
    tipColor: Color = color,
    change: Double? = null,
    higherIsBetter: Boolean? = null,
    changeFmt: (Double) -> String = { "${it.roundToInt()}" },
    chartHeadroom: Float = 0f,
) {
    NoopCard(modifier = modifier, padding = Metrics.cardPadding, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(title)
                    if (subtitle != null) {
                        Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
                if (trailing != null) {
                    Text(trailing, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }

            if (values.size >= 2) {
                ChartWithAxes(
                    values = values,
                    dates = dates,
                    color = color,
                    tipColor = tipColor,
                    formatY = formatY,
                    headroom = chartHeadroom,
                )
            } else {
                SparsePlaceholder()
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { ChartFooter(footer) }
                ChangeChip(change, higherIsBetter, changeFmt)
            }
        }
    }
}

@Composable
private fun ChangeChip(change: Double?, higherIsBetter: Boolean?, fmt: (Double) -> String) {
    if (change == null || kotlin.math.abs(change) <= 0.0001) return
    val sign = if (change >= 0) "+" else "-"
    val color = when (higherIsBetter) {
        null -> Palette.textTertiary
        else -> if ((change > 0) == higherIsBetter) Palette.statusPositive else Palette.metricRose
    }
    TrendChip(text = "$sign${fmt(kotlin.math.abs(change))}", color = color)
}

@Composable
private fun ChartWithAxes(
    values: List<Double>,
    dates: List<String>,
    color: Color,
    formatY: (Double) -> String,
    tipColor: Color = color,
    headroom: Float = 0f,
) {
    val maxV = values.max()
    val avgV = values.average()
    val minV = values.min()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                modifier = Modifier.height(Metrics.chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatY(maxV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                Text(formatY(avgV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                Text(formatY(minV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
            val plotHeight = Metrics.chartHeight * (1f - headroom.coerceIn(0f, 0.5f))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Metrics.chartHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                    LineChart(
                        values = values,
                        modifier = Modifier.fillMaxSize(),
                        color = color,
                        fill = true,
                        selectionEnabled = true,
                        formatValue = formatY,
                    )
                }
            }
        }
        if (dates.size >= 2) {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(dates.first(), dates.getOrNull(dates.lastIndex / 2), dates.last()).forEach { d ->
                    Text(
                        prettyAxisDate(d),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun prettyAxisDate(day: String?): String =
    day?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }
            .getOrDefault(it)
    }.orEmpty()

@Composable
private fun MetricTrendCard(
    title: String,
    unit: String,
    color: Color,
    resolved: ResolvedMetric,
    fmt: (Double) -> String,
    tint: Color? = null,
    tipColor: Color = color,
    higherIsBetter: Boolean? = null,
) {
    val avg = resolved.values.averageOrNull()
    ChartCard(
        title = title,
        subtitle = null,
        trailing = avg?.let { fmt(it) },
        color = color,
        tint = tint,
        tipColor = tipColor,
        values = resolved.values,
        dates = resolved.dates,
        formatY = fmt,
        change = periodChange(resolved.values),
        higherIsBetter = higherIsBetter,
        changeFmt = fmt,
        footer = listOf(
            "Mean" to (avg?.let { "${fmt(it)} $unit" } ?: EM_DASH),
            "Min" to (resolved.values.minOrNull()?.let { fmt(it) } ?: EM_DASH),
            "Max" to (resolved.values.maxOrNull()?.let { fmt(it) } ?: EM_DASH),
        ),
    )
}

private fun periodChange(values: List<Double>): Double? {
    if (values.size < 4) return null
    val mid = values.size / 2
    val earlier = values.take(mid)
    val recent = values.drop(mid)
    if (earlier.isEmpty() || recent.isEmpty()) return null
    return recent.average() - earlier.average()
}

@Composable
private fun ChartFooter(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        HorizontalDivider(color = Palette.hairline)
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEach { (label, value) ->
                Column(modifier = Modifier.weight(1f)) {
                    Overline(label, color = Palette.textTertiary)
                    Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }
        }
    }
}

// MARK: - Recovery history strip

@Composable
private fun RecoveryHistoryCard(days: List<DailyMetric>, range: TrendsRange) {
    val recovery = remember(days, range) {
        val span = (range.days ?: days.size).coerceAtLeast(365)
        days.takeLast(span).mapNotNull { it.recovery }
    }
    val title = if (range == TrendsRange.All && days.size > 365) {
        "Charge, all history"
    } else {
        "Charge, past year"
    }

    NoopCard(tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(title, overline = "Calendar", trailing = "${recovery.size} days")
            if (recovery.size >= 2) {
                BarChart(
                    values = recovery,
                    modifier = Modifier.height(Metrics.trendStripHeight),
                    color = Palette.accent,
                )
            } else {
                SparsePlaceholder(height = Metrics.trendStripHeight)
            }
            HorizontalDivider(color = Palette.hairline)
            Text(
                "Each bar is one day's Charge score, low to high.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Shared bits

@Composable
private fun SparsePlaceholder(height: Dp = Metrics.chartHeight) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Not enough data for this window.",
            style = NoopType.subhead,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyTrends() {
    DataPendingNote(
        title = "Trends need history to draw",
        body = "Trends need history to draw. Import your WHOOP export in Settings " +
            "to see weeks, months and years instantly.",
    )
}

// MARK: - Small numeric helpers

private const val EM_DASH = "-"

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else sum() / size
