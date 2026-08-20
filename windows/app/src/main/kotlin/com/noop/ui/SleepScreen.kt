package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.data.SleepSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sleep — sleep history and detail. A night selector (◀ ▶) walks every recorded
 * night, a hypnogram shows the stage breakdown, and a tile grid shows the key
 * sleep metrics (duration, efficiency, deep, REM, light).
 *
 * Adapted from the Android [SleepScreen]:
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - `LocalContext` / `DatePickerDialog` / `TimePickerDialog` → removed
 *  - `Toast` → removed
 *  - Sleep editing bottom sheet → removed (read-only desktop port)
 *  - Stage JSON parsing kept via `org.json.JSONArray` (JVM-available)
 */
@Composable
fun SleepScreen(
    viewModel: DesktopAppViewModel,
) {
    val sleepSessions by viewModel.sleepSessions.collectAsState()
    val days by viewModel.recentDays.collectAsState()

    // Night selector: 0 = most recent, walking back through every session.
    var nightOffset by remember { mutableIntStateOf(0) }
    val nights = remember(sleepSessions) { sleepSessions.sortedByDescending { it.startTs } }
    val clampedOffset = nightOffset.coerceIn(0, max(0, nights.size - 1))
    val selected = nights.getOrNull(clampedOffset)

    LaunchedEffect(nights.size) {
        if (nightOffset > nights.size - 1) nightOffset = 0
    }

    ScreenScaffold(
        title = "Sleep",
        subtitle = selected?.let { formatSleepDate(it.startTs) } ?: "No sleep recorded",
        leading = {
            if (nights.isNotEmpty()) {
                IconButton(
                    onClick = { if (nightOffset < nights.size - 1) nightOffset++ },
                    enabled = nightOffset < nights.size - 1,
                ) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous night", tint = Palette.textSecondary)
                }
            }
        },
        trailing = {
            if (nights.isNotEmpty()) {
                IconButton(
                    onClick = { if (nightOffset > 0) nightOffset-- },
                    enabled = nightOffset > 0,
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next night", tint = Palette.textSecondary)
                }
            }
        },
    ) {
        if (selected == null) {
            DataPendingNote(
                title = "No sleep data yet",
                body = "Connect a WHOOP strap to record sleep sessions automatically.",
            )
            return@ScreenScaffold
        }

        // --- Hypnogram ---
        SectionHeader("Hypnogram", overline = "Stages")
        NoopCard(padding = 16.dp, tint = Palette.restColor) {
            val stages = parseStages(selected.stagesJSON)
            if (stages.isNotEmpty()) {
                StageFooter(stages = stages)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Palette.hairline)
                Spacer(Modifier.height(12.dp))
                HypnogramWithLabels(stages = stages, modifier = Modifier.fillMaxWidth().height(120.dp))
            } else {
                Text(
                    "No stage data available for this night.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        }

        // --- Sleep metric tiles ---
        SectionHeader("Sleep Metrics", overline = "Detail")
        val durationMin = (selected.endTs - selected.effectiveStartTs) / 60.0
        val efficiency = selected.efficiency
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SleepTile("Duration", formatHours(durationMin), "hours", Modifier.weight(1f))
            SleepTile("Efficiency", efficiency?.let { "${(it * 100.0).roundToInt()}%" } ?: "--", "sleep", Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SleepTile("Avg HRV", selected.avgHrv?.let { "${it.roundToInt()}" } ?: "--", "ms", Modifier.weight(1f))
            SleepTile("Rest HR", selected.restingHr?.let { "$it" } ?: "--", "bpm", Modifier.weight(1f))
        }

        // --- 14-day sleep trend ---
        if (days.isNotEmpty()) {
            SectionHeader("Sleep trend", overline = "14 days")
            val sleepValues = days.takeLast(14).mapNotNull { it.totalSleepMin }
            if (sleepValues.isNotEmpty()) {
                NoopCard(padding = 16.dp) {
                    SleepTrendChart(values = sleepValues, modifier = Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
    }
}

// MARK: - Hypnogram

private data class StageSegment(val start: Long, val end: Long, val stage: String)

private fun parseStages(stagesJSON: String?): List<StageSegment> {
    if (stagesJSON.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(stagesJSON)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optLong("start", -1L)
            val e = o.optLong("end", -1L)
            val stage = o.optString("stage")
            if (s < 0 || e <= s) null else StageSegment(s, e, stage)
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun HypnogramWithLabels(stages: List<StageSegment>, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        // Left column: stage row labels.
        Column(
            modifier = Modifier.width(44.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("Awake" to Palette.sleepAwake, "Light" to Palette.sleepLight,
                "REM" to Palette.sleepREM, "Deep" to Palette.sleepDeep).forEach { (label, color) ->
                Text(
                    label,
                    style = NoopType.caption.copy(fontSize = 9.sp),
                    color = color,
                )
            }
        }
        // Right: the canvas chart.
        Hypnogram(stages = stages, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Hypnogram(stages: List<StageSegment>, modifier: Modifier = Modifier) {
    val stageColor: (String) -> Color = { stage ->
        when (stage.lowercase()) {
            "deep" -> Palette.sleepDeep
            "rem" -> Palette.sleepREM
            "light" -> Palette.sleepLight
            else -> Palette.sleepAwake
        }
    }
    // Y-centre for each stage row. 0 = top (awake), 1 = bottom (deep).
    val stageY: (String) -> Float = { stage ->
        when (stage.lowercase()) {
            "awake", "wake" -> 0.12f
            "light" -> 0.40f
            "rem" -> 0.68f
            "deep" -> 0.90f
            else -> 0.12f
        }
    }
    val minTs = stages.minOf { it.start }
    val maxTs = stages.maxOf { it.end }
    val span = (maxTs - minTs).coerceAtLeast(1L)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // --- Background grid lines ---
        val gridColor = Palette.hairline.copy(alpha = 0.4f)
        for (frac in listOf(0.12f, 0.40f, 0.68f, 0.90f)) {
            val y = h * frac
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 0.5f,
            )
        }

        // --- Filled bands for each segment ---
        val bandH = (h / 5f).coerceAtLeast(6f)
        stages.forEach { seg ->
            val x1 = ((seg.start - minTs).toFloat() / span) * w
            val x2 = ((seg.end - minTs).toFloat() / span) * w
            val y = h * stageY(seg.stage)
            val color = stageColor(seg.stage)
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(x1, y - bandH / 2f),
                size = Size((x2 - x1).coerceAtLeast(2f), bandH),
                cornerRadius = CornerRadius(bandH / 3f, bandH / 3f),
            )
        }

        // --- Step-line connecting segment centres ---
        val sorted = stages.sortedBy { it.start }
        if (sorted.size >= 2) {
            val stepPath = Path()
            for ((i, seg) in sorted.withIndex()) {
                val x1 = ((seg.start - minTs).toFloat() / span) * w
                val x2 = ((seg.end - minTs).toFloat() / span) * w
                val y = h * stageY(seg.stage)
                if (i == 0) stepPath.moveTo(x1, y)
                else stepPath.lineTo(x1, y)
                stepPath.lineTo(x2, y)
            }
            drawPath(
                path = stepPath,
                color = Palette.textTertiary.copy(alpha = 0.35f),
                style = Stroke(width = 1f, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun StageFooter(stages: List<StageSegment>) {
    // Normalise stage keys: "wake" → "awake" so the lookup table matches.
    val totals = stages.groupBy { it.stage.lowercase().let { s -> if (s == "wake") "awake" else s } }
        .mapValues { e -> e.value.sumOf { it.end - it.start } }
    val totalSec = totals.values.sum().coerceAtLeast(1L)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("deep" to Palette.sleepDeep, "rem" to Palette.sleepREM, "light" to Palette.sleepLight, "awake" to Palette.sleepAwake).forEach { (name, color) ->
            val sec = totals[name] ?: 0L
            val pct = (sec * 100.0 / totalSec).roundToInt()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(name.replaceFirstChar { it.uppercase() }, style = NoopType.footnote, color = color, maxLines = 1)
                Text(formatHours(sec / 60.0), style = NoopType.captionNumber, color = Palette.textPrimary, maxLines = 1)
                Text("$pct%", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
        }
    }
}

// MARK: - Sleep tile

@Composable
private fun SleepTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = 14.dp, tint = Palette.restColor) {
        Column {
            Overline(label, color = Palette.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(value, style = NoopType.tileValue, color = Palette.restColor)
            Text(caption, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - Sleep trend chart

@Composable
private fun SleepTrendChart(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxVal = values.max()
        val minVal = values.min()
        val range = (maxVal - minVal).coerceAtLeast(0.001)
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - ((v - minVal) / range).toFloat() * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Palette.restColor, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

// MARK: - Helpers

private fun formatHours(minutes: Double): String {
    val h = (minutes / 60.0).toInt()
    val m = (minutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatSleepDate(ts: Long): String {
    val dt = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    return dt.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.US))
}
