package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.noop.data.DailyMetric
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - Stress Monitor (desktop port of Android StressScreen)
//
// A Whoop-style "Stress Monitor": one 0-3 number, a band (LOW/MEDIUM/HIGH), and a
// single plain-English line on *why*. The score is a transparent proxy for autonomic
// load, DERIVED from how today's resting HR / HRV sit against a personal 30-day
// baseline (a stored "stress" series, if present, takes priority).
//
// Desktop differences from Android:
//  - No liquid UI (LiquidScreenSky / LiquidVessel / staggeredAppear) — plain NoopCard surfaces
//  - No intraday timeline scrubber — the trend chart covers the longitudinal view
//  - collectAsState() instead of collectAsStateWithLifecycle()
//  - NoopPrefs via java.util.prefs (no Context needed)

// MARK: - StressModel

/** The computed stress read-out for the current day. */
data class StressModel(
    val score: Double,
    val band: StressBand,
    val explanation: String,
    val todayRhr: Int?,
    val todayHrv: Double?,
    val baselineRhr: Double?,
    val baselineHrv: Double?,
    val zRhr: Double?,
    val zHrv: Double?,
    /** The stored daily "stress" value if one was used (null = z-score derived). */
    val source: StressSource,
    /** The 30-day trend of stress scores (oldest first). */
    val trend: List<TrendPoint>,
    /** Percentage of the last 30 charted days with score < 1.0 (calm time). */
    val calmPct: Double,
)

enum class StressSource { STORED, DERIVED }

data class TrendPoint(val day: String, val score: Double)

enum class StressBand(val title: String, val tone: StrandTone) {
    Low("Low", StrandTone.Positive),
    Medium("Medium", StrandTone.Warning),
    High("High", StrandTone.Critical);

    companion object {
        fun from(score: Double): StressBand = when {
            score < 1.0 -> Low
            score < 2.0 -> Medium
            else -> High
        }
    }
}

object StressModelBuilder {
    /**
     * Build the model from recent days + an optional stored "stress" series.
     * Returns null when there is insufficient baseline data (< 7 days of RHR+HRV).
     */
    fun build(days: List<DailyMetric>, stored: Map<String, Double>): StressModel? {
        if (days.isEmpty()) return null

        // Baseline: up to 30 days excluding today.
        val today = days.lastOrNull()
        val baselineDays = days.dropLast(1).takeLast(30)
        if (baselineDays.size < 7) return null

        val rhrVals = baselineDays.mapNotNull { it.restingHr?.toDouble() }
        val hrvVals = baselineDays.mapNotNull { it.avgHrv }
        if (rhrVals.size < 5 || hrvVals.size < 5) return null

        val meanRhr = rhrVals.average()
        val sdRhr = rhrVals.stdev().coerceAtLeast(1.0)
        val meanHrv = hrvVals.average()
        val sdHrv = hrvVals.stdev().coerceAtLeast(1.0)

        val todayRhr = today?.restingHr?.toDouble()
        val todayHrv = today?.avgHrv

        // Source priority: stored value > z-score derivation.
        val todayKey = today?.day ?: ""
        val storedVal = stored[todayKey]

        val (score, source) = if (storedVal != null) {
            storedVal.coerceIn(0.0, 3.0) to StressSource.STORED
        } else if (todayRhr != null && todayHrv != null) {
            val zR = (todayRhr - meanRhr) / sdRhr
            val zH = (meanHrv - todayHrv) / sdHrv
            val raw = zR + zH
            (3.0 / (1.0 + exp(-raw))).coerceIn(0.0, 3.0) to StressSource.DERIVED
        } else {
            return null // no way to compute today's score
        }

        val zRhr = if (todayRhr != null) (todayRhr - meanRhr) / sdRhr else null
        val zHrv = if (todayHrv != null) (meanHrv - todayHrv) / sdHrv else null

        val band = StressBand.from(score)

        // Build trend: up to 30 most recent days, preferring stored values, deriving from z-scores.
        val trend = days.takeLast(30).mapNotNull { d ->
            val sv = stored[d.day]
            if (sv != null) {
                TrendPoint(d.day, sv.coerceIn(0.0, 3.0))
            } else if (d.restingHr != null && d.avgHrv != null) {
                val zR = (d.restingHr.toDouble() - meanRhr) / sdRhr
                val zH = (meanHrv - d.avgHrv) / sdHrv
                val raw = zR + zH
                TrendPoint(d.day, (3.0 / (1.0 + exp(-raw))).coerceIn(0.0, 3.0))
            } else null
        }

        val calmCount = trend.count { it.score < 1.0 }
        val calmPct = if (trend.isNotEmpty()) calmCount.toDouble() / trend.size else 0.0

        val explanation = buildExplanation(band, todayRhr, meanRhr, todayHrv, meanHrv)

        return StressModel(
            score = score,
            band = band,
            explanation = explanation,
            todayRhr = todayRhr?.toInt(),
            todayHrv = todayHrv,
            baselineRhr = meanRhr,
            baselineHrv = meanHrv,
            zRhr = zRhr,
            zHrv = zHrv,
            source = source,
            trend = trend,
            calmPct = calmPct,
        )
    }

    private fun buildExplanation(
        band: StressBand,
        todayRhr: Double?,
        meanRhr: Double,
        todayHrv: Double?,
        meanHrv: Double,
    ): String {
        val rhrDir = if (todayRhr != null && todayRhr > meanRhr) "up" else if (todayRhr != null) "down" else null
        val hrvDir = if (todayHrv != null && todayHrv < meanHrv) "down" else if (todayHrv != null) "up" else null
        return when {
            band == StressBand.Low -> "Your autonomic load is low. HRV and resting HR are within your normal range."
            rhrDir == "up" && hrvDir == "down" -> "Resting HR is up and HRV is down vs. your 30-day baseline, pushing autonomic load higher."
            rhrDir == "up" -> "Resting HR is elevated vs. your 30-day baseline."
            hrvDir == "down" -> "HRV is suppressed vs. your 30-day baseline."
            else -> "Your autonomic load is ${band.title.lowercase()}, with HRV and resting HR near your 30-day average."
        }
    }

    private fun List<Double>.stdev(): Double {
        if (size < 2) return 0.0
        val m = average()
        val variance = map { (it - m) * (it - m) }.sum() / (size - 1)
        return sqrt(variance)
    }
}

// MARK: - StressRamp (colour ramp matching the Android twin)

private object StressRamp {
    val CALM = Palette.accent
    val STEADY = Palette.statusPositive
    val TENSE = Palette.statusWarning

    val stops = listOf(
        0.0f to CALM,
        0.33f to CALM,
        0.34f to STEADY,
        0.66f to STEADY,
        0.67f to TENSE,
        1.0f to TENSE,
    )

    fun color(score: Double): Color = when {
        score < 1.0 -> CALM
        score < 2.0 -> STEADY
        else -> TENSE
    }
}

// MARK: - StressScreen

@Composable
fun StressScreen(viewModel: DesktopAppViewModel) {
    val days by viewModel.recentDays.collectAsState()

    // Stored daily "stress" values (0-3), keyed by day.
    var stored by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var storedLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val rows = runCatching {
            viewModel.repository.metricSeries(viewModel.activeStrapId, "stress", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList())
        stored = rows.associate { it.day to it.value.coerceIn(0.0, 3.0) }
        storedLoaded = true
    }

    val model = remember(days, stored) { StressModelBuilder.build(days, stored) }

    LazyScreenScaffold(
        title = "Stress",
        subtitle = "Autonomic load from HRV and resting heart rate",
    ) {
        when {
            model != null -> {
                item { StressHeroCard(model) }
                item { StressMarkersSection(model) }
                item { StressTrendSection(model) }
                item { StressMethodologyCard(model) }
            }
            !storedLoaded -> item { StressLoading() }
            else -> item { StressEmpty() }
        }
    }
}

// MARK: - Hero card

@Composable
private fun StressHeroCard(model: StressModel) {
    val bandColor = StressRamp.color(model.score)
    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline("Stress monitor", modifier = Modifier.weight(1f))
                StatePill(model.band.title, tone = model.band.tone, showsDot = true)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // PipBar stand-in: a 20-segment progress bar on the 0-3 scale.
                PipBarDesktop(score = model.score, maxScore = 3.0, tint = bandColor)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            model.band.title,
                            style = NoopType.display(30f),
                            color = bandColor,
                        )
                        Text(
                            "of 3",
                            style = NoopType.number(14f, FontWeight.Medium),
                            color = Palette.textTertiary,
                            modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
                        )
                    }
                    Text(
                        "on the 0-3 autonomic-load scale",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }

            Text(
                model.explanation,
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            // Source badge
            val sourceText = when (model.source) {
                StressSource.STORED -> "From your strap's stored stress series"
                StressSource.DERIVED -> "Derived from HRV & resting HR vs. 30-day baseline"
            }
            Text(sourceText, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - PipBar desktop stand-in

@Composable
private fun PipBarDesktop(score: Double, maxScore: Double, tint: Color) {
    val fraction = (score / maxScore).coerceIn(0.0, 1.0).toFloat()
    val segs = 20
    val filled = (fraction * segs).roundToInt()
    Canvas(
        modifier = Modifier.size(width = 120.dp, height = 56.dp),
    ) {
        val segW = (size.width / segs) * 0.8f
        val gap = (size.width / segs) * 0.2f
        val segH = size.height
        for (i in 0 until segs) {
            val x = i * (segW + gap)
            val active = i < filled
            drawRoundRect(
                color = if (active) tint else tint.copy(alpha = 0.12f),
                topLeft = Offset(x, 0f),
                size = Size(segW, segH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(segW * 0.3f),
            )
        }
    }
}

// MARK: - Markers section

@Composable
private fun StressMarkersSection(model: StressModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Today", overline = "Markers", trailing = "vs 30-day baseline")
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Resting HR",
                value = model.todayRhr?.let { "$it bpm" } ?: "--",
                caption = "Baseline ${model.baselineRhr?.let { String.format(Locale.US, "%.0f", it) } ?: "--"} bpm",
                accent = if (model.zRhr != null && model.zRhr > 0) StressRamp.TENSE else StressRamp.CALM,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "HRV",
                value = model.todayHrv?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                caption = "Baseline ${model.baselineHrv?.let { String.format(Locale.US, "%.0f", it) } ?: "--"} ms",
                accent = if (model.zHrv != null && model.zHrv > 0) StressRamp.TENSE else StressRamp.CALM,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Calm time",
                value = String.format(Locale.US, "%.0f%%", model.calmPct * 100),
                caption = "of last 30 days below 1.0",
                accent = StressRamp.CALM,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "z-RHR",
                value = model.zRhr?.let { String.format(Locale.US, "%+.1f", it) } ?: "--",
                caption = "Standard deviations above baseline",
                accent = if (model.zRhr != null && model.zRhr > 0) StressRamp.TENSE else StressRamp.CALM,
            )
        }
    }
}

// MARK: - Trend section

@Composable
private fun StressTrendSection(model: StressModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Trend", overline = "30-day", trailing = "autonomic load")
        if (model.trend.size >= 2) {
            NoopCard(tint = Palette.stressColor) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline("Daily stress score")
                    StressTrendChart(model.trend)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(model.trend.first().day.takeLast(5), style = NoopType.footnote, color = Palette.textTertiary)
                        Text(model.trend.last().day.takeLast(5), style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Text(
                        "Each point is one day's 0-3 autonomic-load score. Blue = calm, green = moderate, amber = high.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        } else {
            NoopCard {
                Text("Not enough trend data yet. Keep syncing to build your 30-day baseline.", style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

@Composable
private fun StressTrendChart(trend: List<TrendPoint>) {
    val scores = trend.map { it.score }
    val gradient = Brush.horizontalGradient(*StressRamp.stops.toTypedArray())
    val hairline = Palette.hairline
    val textTertiary = Palette.textTertiary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || scores.size < 2) return@Canvas

        val topPad = 12f
        val botPad = 12f
        val usable = (h - topPad - botPad).coerceAtLeast(1f)
        val stepX = w / (scores.size - 1)

        // Grid lines at 0, 1, 2, 3
        listOf(0.0, 1.0, 2.0, 3.0).forEach { lvl ->
            val y = topPad + (1f - (lvl / 3.0).toFloat()) * usable
            drawLine(hairline, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // Build the line path
        val path = Path().apply {
            val x0 = 0f
            val y0 = topPad + (1f - (scores[0] / 3.0).toFloat()) * usable
            moveTo(x0, y0)
            for (i in 1 until scores.size) {
                val x = i * stepX
                val y = topPad + (1f - (scores[i] / 3.0).toFloat()) * usable
                lineTo(x, y)
            }
        }

        // Fill under the line
        val fillPath = Path().apply {
            val x0 = 0f
            val y0 = topPad + (1f - (scores[0] / 3.0).toFloat()) * usable
            moveTo(x0, h - botPad)
            lineTo(x0, y0)
            for (i in 1 until scores.size) {
                val x = i * stepX
                val y = topPad + (1f - (scores[i] / 3.0).toFloat()) * usable
                lineTo(x, y)
            }
            lineTo((scores.size - 1) * stepX, h - botPad)
            close()
        }

        drawPath(fillPath, brush = gradient, alpha = 0.15f)
        drawPath(path, brush = gradient, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Dots
        for (i in scores.indices) {
            val x = i * stepX
            val y = topPad + (1f - (scores[i] / 3.0).toFloat()) * usable
            drawCircle(StressRamp.color(scores[i]), radius = 3f, center = Offset(x, y))
        }
    }
}

// MARK: - Methodology card

@Composable
private fun StressMethodologyCard(model: StressModel) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Overline("How this is computed")
            Text(
                "The 0-3 score is a transparent proxy for autonomic load, derived from how " +
                    "today's resting HR and HRV sit against your personal 30-day baseline:",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            HorizontalDivider(color = Palette.hairline)
            MethodologyRow("z-RHR", "(today RHR \u2212 mean RHR) / SD", model.zRhr?.let { String.format(Locale.US, "%+.2f", it) })
            MethodologyRow("z-HRV", "(mean HRV \u2212 today HRV) / SD", model.zHrv?.let { String.format(Locale.US, "%+.2f", it) })
            MethodologyRow("raw", "z-RHR + z-HRV", null)
            MethodologyRow("stress", "3 / (1 + e^(\u2212raw))", String.format(Locale.US, "%.2f", model.score))
            HorizontalDivider(color = Palette.hairline)
            Text(
                "Bands: 0-1 Low \u00b7 1-2 Medium \u00b7 2-3 High. " +
                    "A stored daily stress value from your strap takes priority over the z-score derivation.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun MethodologyRow(label: String, formula: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = NoopType.caption, color = Palette.textPrimary, modifier = Modifier.width(60.dp))
        Text(formula, style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = NoopType.number(13f, FontWeight.Medium), color = Palette.accent)
        }
    }
}

// MARK: - States

@Composable
private fun StressLoading() {
    NoopCard {
        Text("Loading stress data\u2026", style = NoopType.subhead, color = Palette.textSecondary)
    }
}

@Composable
private fun StressEmpty() {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Not enough data yet", style = NoopType.title2, color = Palette.textPrimary)
            Text(
                "Stress monitoring needs at least 7 days of resting HR and HRV data to establish a baseline. " +
                    "Keep syncing your strap to build up your history.",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}
