package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - CompareMetric + catalog
//
// Desktop port of the Android CompareScreen. Lets users pick 2–4 metrics and
// overlay them on a single normalized chart (0–100%), with Pearson correlation
// analysis between each pair.
//
// Porting rules: collectAsState() replaces collectAsStateWithLifecycle(); no
// Android Context; liquid UI components skipped; only existing Windows
// components (NoopCard, Overline, SectionHeader, ScreenScaffold,
// SegmentedPillControl, Palette, NoopType, Metrics) are used.

private data class CompareMetric(
    val key: String,
    val title: String,
    val category: String,
    val unit: String,
    val source: String,
    val decimals: Int,
    val format: (Double) -> String,
)

private val METRIC_CATALOG: List<CompareMetric> = listOf(
    CompareMetric("recovery", "Recovery", "Recovery", "%", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("strain", "Strain", "Recovery", "/ 21", "WHOOP", 1) { String.format(Locale.US, "%.1f", it) },
    CompareMetric("avgHrv", "HRV", "Recovery", "ms", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("restingHr", "Resting HR", "Recovery", "bpm", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("totalSleepMin", "Sleep", "Sleep", "hr", "WHOOP", 1) { String.format(Locale.US, "%.1f", it / 60.0) },
    CompareMetric("efficiency", "Efficiency", "Sleep", "%", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("deepMin", "Deep", "Sleep", "min", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("remMin", "REM", "Sleep", "min", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("lightMin", "Light", "Sleep", "min", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("disturbances", "Disturbances", "Sleep", "count", "WHOOP", 0) { "${it.roundToInt()}" },
    CompareMetric("spo2Pct", "SpO₂", "Vital", "%", "WHOOP", 1) { String.format(Locale.US, "%.1f", it) },
    CompareMetric("skinTempDevC", "Skin Temp", "Vital", "°C", "WHOOP", 1) { String.format(Locale.US, "%+.1f", it) },
    CompareMetric("respRateBpm", "Resp Rate", "Vital", "bpm", "WHOOP", 1) { String.format(Locale.US, "%.1f", it) },
    CompareMetric("steps", "Steps", "Activity", "count", "Est.", 0) { "%,d".format(Locale.US, it.roundToInt()) },
    CompareMetric("activeKcalEst", "Active Kcal", "Activity", "kcal", "Est.", 0) { it.roundToInt().toString() },
    CompareMetric("exerciseCount", "Workouts", "Activity", "count", "WHOOP", 0) { "${it.roundToInt()}" },
)

private fun metricByKey(key: String): CompareMetric = METRIC_CATALOG.first { it.key == key }

private fun extractValue(day: DailyMetric, key: String): Double? = when (key) {
    "recovery" -> day.recovery
    "strain" -> day.strain
    "avgHrv" -> day.avgHrv
    "restingHr" -> day.restingHr?.toDouble()
    "totalSleepMin" -> day.totalSleepMin
    "efficiency" -> day.efficiency
    "deepMin" -> day.deepMin
    "remMin" -> day.remMin
    "lightMin" -> day.lightMin
    "disturbances" -> day.disturbances?.toDouble()
    "spo2Pct" -> day.spo2Pct
    "skinTempDevC" -> day.skinTempDevC
    "respRateBpm" -> day.respRateBpm
    "steps" -> day.steps?.toDouble()
    "activeKcalEst" -> day.activeKcalEst
    "exerciseCount" -> day.exerciseCount?.toDouble()
    else -> null
}

private fun metricColor(index: Int): Color = when (index % 4) {
    0 -> Palette.accent
    1 -> Palette.statusPositive
    2 -> Palette.statusWarning
    else -> Palette.metricPurple
}

// MARK: - Range

private enum class CompareRange(val days: Int?, val label: String) {
    Week(7, "7d"), Month(30, "30d"), Quarter(90, "90d"), All(null, "All");
}

// MARK: - Resolved series

private data class MetricSeries(
    val metric: CompareMetric,
    val colorIndex: Int,
    val dayValues: List<Pair<String, Double>>,
    val normalized: List<Double>,
    val min: Double,
    val max: Double,
)

// MARK: - Pearson correlation

private fun pearson(xs: List<Double>, ys: List<Double>): Double {
    val n = xs.size
    if (n < 2) return 0.0
    val mx = xs.sum() / n
    val my = ys.sum() / n
    var num = 0.0
    var dx2 = 0.0
    var dy2 = 0.0
    for (i in 0 until n) {
        val dx = xs[i] - mx
        val dy = ys[i] - my
        num += dx * dy
        dx2 += dx * dx
        dy2 += dy * dy
    }
    val denom = sqrt(dx2 * dy2)
    return if (denom > 0.0) num / denom else 0.0
}

private fun alignedPearson(a: MetricSeries, b: MetricSeries): Double? {
    val aMap = a.dayValues.toMap()
    val bMap = b.dayValues.toMap()
    val common = aMap.keys.filter { it in bMap }
    if (common.size < 3) return null
    return pearson(common.map { aMap[it]!! }, common.map { bMap[it]!! })
}

private fun correlationReading(r: Double?): String {
    if (r == null) return "N/A"
    val a = abs(r)
    return when {
        a >= 0.7 -> if (r > 0) "Strong +" else "Strong −"
        a >= 0.4 -> if (r > 0) "Moderate +" else "Moderate −"
        a >= 0.2 -> if (r > 0) "Weak +" else "Weak −"
        else -> "Negligible"
    }
}

// MARK: - Main screen

@Composable
fun CompareScreen(viewModel: DesktopAppViewModel) {
    val days by viewModel.recentDays.collectAsState()

    var selectedKeys by remember { mutableStateOf(listOf("recovery", "strain")) }
    var range by remember { mutableStateOf(CompareRange.Month) }

    val windowedDays = remember(days, range) {
        when (val n = range.days) {
            null -> days
            else -> {
                val cutoff = LocalDate.now().minusDays((n - 1).toLong()).toString()
                days.filter { it.day >= cutoff }
            }
        }
    }

    val series = remember(windowedDays, selectedKeys) {
        selectedKeys.mapIndexedNotNull { idx, key ->
            val points = windowedDays.mapNotNull { d ->
                extractValue(d, key)?.let { d.day to it }
            }
            if (points.size >= 2) {
                val values = points.map { it.second }
                val minV = values.min()
                val maxV = values.max()
                val span = maxV - minV
                val norm = points.map { (_, v) -> if (span > 0.0) (v - minV) / span else 0.5 }
                MetricSeries(metricByKey(key), idx, points, norm, minV, maxV)
            } else null
        }
    }

    ScreenScaffold(
        title = "Compare",
        subtitle = "Overlay metrics to find correlations.",
    ) {
        // Range selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedPillControl(
                items = CompareRange.entries.toList(),
                selection = range,
                label = { it.label },
                onSelect = { range = it },
            )
            Spacer(Modifier.weight(1f))
            Overline("${windowedDays.size} days", color = Palette.textTertiary)
        }

        // Metric picker
        MetricPickerRow(
            selectedKeys = selectedKeys,
            onRemove = { key ->
                if (selectedKeys.size > 1) selectedKeys = selectedKeys.filter { it != key }
            },
            onAdd = { key ->
                if (key !in selectedKeys && selectedKeys.size < 4) selectedKeys = selectedKeys + key
            },
        )

        if (series.size >= 2) {
            // Overlay chart
            NoopCard(tint = Palette.accent) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    SectionHeader("Overlay", overline = "Normalized 0–100%")
                    OverlayChart(series = series, allDates = windowedDays.map { it.day })
                    ChartLegend(series = series)
                }
            }

            // Correlation matrix
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    SectionHeader("Correlation", overline = "Pearson r")
                    CorrelationMatrix(series = series)
                }
            }
        } else if (selectedKeys.size >= 2) {
            DataPendingNote(
                title = "Not enough data in this range",
                body = "Some selected metrics don't have enough readings in the ${range.label} window. Try a wider range.",
            )
        } else {
            DataPendingNote(
                title = "Select at least 2 metrics",
                body = "Pick 2–4 metrics above to overlay them on a single chart and see how they correlate.",
            )
        }
    }
}

// MARK: - Metric picker

@Composable
private fun MetricPickerRow(
    selectedKeys: List<String>,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        selectedKeys.forEachIndexed { idx, key ->
            val metric = metricByKey(key)
            MetricChip(
                label = metric.title,
                color = metricColor(idx),
                unit = metric.unit,
                onRemove = { onRemove(key) },
                canRemove = selectedKeys.size > 1,
            )
        }
        if (selectedKeys.size < 4) {
            Box {
                val addShape = RoundedCornerShape(50)
                Row(
                    modifier = Modifier
                        .clip(addShape)
                        .background(Palette.surfaceInset)
                        .border(1.dp, Palette.hairline, addShape)
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add metric",
                        tint = Palette.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Add", style = NoopType.caption, color = Palette.textSecondary)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    METRIC_CATALOG.filter { it.key !in selectedKeys }.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(m.title, style = NoopType.body, color = Palette.textPrimary)
                                    Text(
                                        "${m.category} · ${m.unit}",
                                        style = NoopType.footnote,
                                        color = Palette.textTertiary,
                                    )
                                }
                            },
                            onClick = {
                                onAdd(m.key)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    color: Color,
    unit: String,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.32f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = NoopType.caption,
            color = Palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(unit, style = NoopType.footnote, color = Palette.textTertiary)
        if (canRemove) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = color,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

// MARK: - Overlay chart

@Composable
private fun OverlayChart(
    series: List<MetricSeries>,
    allDates: List<String>,
) {
    val colors = series.map { metricColor(it.colorIndex) }
    val dayToIndex = remember(allDates) {
        allDates.mapIndexed { i, d -> d to i }.toMap()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Y-axis labels
            Column(
                modifier = Modifier.height(Metrics.chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("100%", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                Text("0%", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
            // Chart canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Metrics.chartHeight)
                    .clip(RoundedCornerShape(Metrics.cornerSm))
                    .background(Palette.surfaceInset),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val n = allDates.size
                    if (n < 2 || size.width <= 0f) return@Canvas
                    val strokePx = 2.5f
                    val topPad = strokePx + 6f
                    val bottomPad = strokePx + 6f
                    val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                    series.forEachIndexed { sIdx, s ->
                        val pts = s.dayValues.mapIndexed { i, (day, _) ->
                            val dayIdx = dayToIndex[day] ?: 0
                            val x = if (n > 1) (dayIdx.toFloat() / (n - 1)) * size.width else 0f
                            val norm = s.normalized[i].toFloat()
                            val y = topPad + (1f - norm) * usableH
                            Offset(x, y)
                        }
                        if (pts.size >= 2) {
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (j in 1 until pts.size) lineTo(pts[j].x, pts[j].y)
                            }
                            drawPath(
                                path = path,
                                color = colors[sIdx],
                                style = Stroke(
                                    width = strokePx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }
                }
            }
        }
        // X-axis labels
        if (allDates.size >= 2) {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    allDates.first(),
                    allDates.getOrNull(allDates.lastIndex / 2),
                    allDates.last(),
                ).forEach { d ->
                    Text(
                        prettyDate(d),
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

@Composable
private fun ChartLegend(series: List<MetricSeries>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        series.forEach { s ->
            val color = metricColor(s.colorIndex)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    s.metric.title,
                    style = NoopType.caption,
                    color = Palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${s.metric.format(s.min)}–${s.metric.format(s.max)}",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

private fun prettyDate(day: String?): String =
    day?.let {
        runCatching {
            LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
        }.getOrDefault(it)
    }.orEmpty()

// MARK: - Correlation matrix

@Composable
private fun CorrelationMatrix(series: List<MetricSeries>) {
    val n = series.size
    val colors = series.map { metricColor(it.colorIndex) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        // Header row
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(0.6f))
            series.forEachIndexed { j, s ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colors[j]))
                        Text(
                            s.metric.title,
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Data rows
        series.forEachIndexed { i, si ->
            Row(modifier = Modifier.fillMaxWidth()) {
                // Row header
                Box(
                    modifier = Modifier.weight(0.6f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                        Text(
                            si.metric.title,
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Cells
                series.forEachIndexed { j, _ ->
                    CorrelationCell(
                        r = if (i == j) 1.0 else alignedPearson(si, series[j]),
                        isSelf = i == j,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CorrelationCell(
    r: Double?,
    isSelf: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        isSelf -> Palette.textTertiary
        r == null -> Palette.textTertiary
        r > 0 -> Palette.statusPositive
        r < 0 -> Palette.metricRose
        else -> Palette.textTertiary
    }
    val bgAlpha = if (isSelf || r == null) 0.08f
        else (abs(r).toFloat() * 0.3f).coerceIn(0.05f, 0.4f)

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(Metrics.cornerXs))
            .background(tint.copy(alpha = bgAlpha))
            .border(1.dp, Palette.hairline.copy(alpha = 0.4f), RoundedCornerShape(Metrics.cornerXs)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isSelf) "1.00" else r?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
                style = NoopType.captionNumber,
                color = Palette.textPrimary,
                maxLines = 1,
            )
            if (!isSelf && r != null) {
                Text(
                    correlationReading(r),
                    style = NoopType.footnote.copy(fontSize = 9.sp),
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}